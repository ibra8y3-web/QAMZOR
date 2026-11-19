#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64
#include "qzra.h"
#include "qzra_json.h"

#include <ctype.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <zlib.h>

#define HEADER_SIZE 32u
#define MAX_MANIFEST (64u * 1024u * 1024u)
#define MAX_ENTRIES 1000000u
#define MAX_CHUNK (1024u * 1024u)
#define MAX_LEGACY_BLOCK (16u * 1024u * 1024u)
#define MAX_SCOPES 32u
#define GCM_TAG 16u

typedef struct {
    uint8_t id[16];
    char id_hex[33];
    char *path;
    uint8_t salt[16];
    uint8_t check_nonce[12];
    uint8_t check_tag[16];
    uint8_t key[32];
    int unlocked;
} scope_t;

typedef struct {
    uint64_t offset, csize, size;
    int zlib;
    uint8_t sha[32];
    int has_nonce;
    uint8_t nonce[12];
    size_t scope;          /* 0 = none, otherwise scope index + 1 */
} chunk_t;

typedef struct {
    char *path;
    int is_dir;
    uint64_t size;
    uint8_t sha[32];
    chunk_t *chunks;
    size_t nchunks;
    size_t scope;          /* governing protected folder, 0 = none */
} entry_t;

struct qzra_archive {
    FILE *fp;
    uint64_t file_len, payload_start, payload_len;
    int minor;
    uint8_t root_key[32];
    int has_root_key;
    entry_t *entries;
    size_t nentries;
    scope_t *scopes;
    size_t nscopes;
    qzra_entry *view;      /* public view of entries */
};

/* ---------------------------------------------------------------- errors */
static qzra_status fail(qzra_error *err, qzra_status st, const char *fmt, ...) {
    if (err) {
        err->status = st;
        va_list ap; va_start(ap, fmt);
        vsnprintf(err->message, sizeof err->message, fmt, ap);
        va_end(ap);
    }
    return st;
}

const char *qzra_version(void) { return QZRA_SDK_VERSION; }

const char *qzra_status_name(qzra_status s) {
    switch (s) {
    case QZRA_OK: return "OK";
    case QZRA_ERR_INVALID_ARGUMENT: return "INVALID_ARGUMENT";
    case QZRA_ERR_IO: return "IO_ERROR";
    case QZRA_ERR_NO_MEMORY: return "NO_MEMORY";
    case QZRA_ERR_INVALID_FORMAT: return "INVALID_FORMAT";
    case QZRA_ERR_UNSUPPORTED_VERSION: return "UNSUPPORTED_VERSION";
    case QZRA_ERR_UNSAFE_PATH: return "UNSAFE_PATH";
    case QZRA_ERR_AUTHENTICATION_FAILED: return "AUTHENTICATION_FAILED";
    case QZRA_ERR_INTEGRITY_FAILED: return "INTEGRITY_FAILED";
    case QZRA_ERR_PASSWORD_REQUIRED: return "PASSWORD_REQUIRED";
    case QZRA_ERR_FOLDER_LOCKED: return "FOLDER_LOCKED";
    case QZRA_ERR_LIMIT: return "LIMIT_EXCEEDED";
    case QZRA_ERR_EXISTS: return "ALREADY_EXISTS";
    }
    return "UNKNOWN";
}

/* ---------------------------------------------------------------- helpers */
static uint64_t be64(const uint8_t *p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | p[i];
    return v;
}
static uint32_t be32(const uint8_t *p) { return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3]; }
static uint16_t be16(const uint8_t *p) { return (uint16_t)((p[0] << 8) | p[1]); }
static void put_be64(uint8_t *p, uint64_t v) { for (int i = 7; i >= 0; i--) { p[i] = (uint8_t)v; v >>= 8; } }

static int hex_val(int c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}
/* Strict lowercase hex of exactly `chars` characters. */
static int hex_decode(const char *s, size_t chars, uint8_t *out) {
    if (!s || strlen(s) != chars || chars % 2) return -1;
    for (size_t i = 0; i < chars; i += 2) {
        int a = hex_val(s[i]), b = hex_val(s[i + 1]);
        if (a < 0 || b < 0) return -1;
        out[i / 2] = (uint8_t)((a << 4) | b);
    }
    return 0;
}

static int sha256_buf(const uint8_t *data, size_t len, uint8_t out[32]) {
    unsigned int n = 0;
    return EVP_Digest(data, len, out, &n, EVP_sha256(), NULL) == 1 && n == 32 ? 0 : -1;
}

static int scrypt_key(const char *password, const uint8_t salt[16], uint8_t out[32]) {
    /* QZRA fixes N=32768, r=8, p=1: ~32 MiB working memory, so raise OpenSSL's default cap. */
    return EVP_PBE_scrypt(password, strlen(password), salt, 16, 32768, 8, 1, 128u * 1024u * 1024u, out, 32) == 1 ? 0 : -1;
}

static int subkey(const uint8_t master[32], const char *purpose, uint8_t out[32]) {
    uint8_t msg[64];
    static const char prefix[] = "QZRA-1.3-SUBKEY"; /* followed by a NUL byte */
    size_t plen = strlen(purpose), n = sizeof prefix; /* includes the NUL */
    if (n + plen > sizeof msg) return -1;
    memcpy(msg, prefix, n);
    memcpy(msg + n, purpose, plen);
    unsigned int outlen = 0;
    return HMAC(EVP_sha256(), master, 32, msg, n + plen, out, &outlen) && outlen == 32 ? 0 : -1;
}

/* AES-256-GCM decrypt. `ct` contains ciphertext followed by the 16-byte tag. Returns 0 on success,
 * -1 on authentication failure, -2 on any other error. `out` needs ctlen - 16 bytes (may be NULL when 0). */
static int aead_decrypt(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aadlen,
                        const uint8_t *ct, size_t ctlen, uint8_t *out) {
    if (ctlen < GCM_TAG || ctlen - GCM_TAG > 0x7fffffffu || aadlen > 0x7fffffffu) return -2;
    size_t plen = ctlen - GCM_TAG;
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -2;
    int rc = -2, n = 0;
    uint8_t dummy;
    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), NULL, NULL, NULL) != 1) goto done;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, 12, NULL) != 1) goto done;
    if (EVP_DecryptInit_ex(ctx, NULL, NULL, key, nonce) != 1) goto done;
    if (aadlen && EVP_DecryptUpdate(ctx, NULL, &n, aad, (int)aadlen) != 1) goto done;
    if (plen && EVP_DecryptUpdate(ctx, out, &n, ct, (int)plen) != 1) goto done;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, GCM_TAG, (void *)(uintptr_t)(ct + plen)) != 1) goto done;
    rc = EVP_DecryptFinal_ex(ctx, &dummy, &n) == 1 ? 0 : -1;
done:
    EVP_CIPHER_CTX_free(ctx);
    return rc;
}

static int file_seek(FILE *fp, uint64_t pos) { return fseeko(fp, (off_t)pos, SEEK_SET); }

/* ---------------------------------------------------------------- path rules */
static int path_is_safe(const char *name, size_t len) {
    if (len == 0 || strlen(name) != len) return 0;
    if (strchr(name, '\\') || strchr(name, ':') || name[0] == '/') return 0;
    size_t i = 0;
    while (i <= len) {
        size_t j = i;
        while (j < len && name[j] != '/') j++;
        size_t clen = j - i;
        if (clen == 0) return 0;
        if ((clen == 1 && name[i] == '.') || (clen == 2 && name[i] == '.' && name[i + 1] == '.')) return 0;
        /* Windows reserved device names: stem before the first '.' */
        size_t stem = 0;
        while (stem < clen && name[i + stem] != '.') stem++;
        char up[8];
        if (stem <= 4 && stem >= 3) {
            for (size_t k = 0; k < stem; k++) up[k] = (char)toupper((unsigned char)name[i + k]);
            up[stem] = '\0';
            if (!strcmp(up, "CON") || !strcmp(up, "PRN") || !strcmp(up, "AUX") || !strcmp(up, "NUL")) return 0;
            if (stem == 4 && up[3] >= '1' && up[3] <= '9' && (!strncmp(up, "COM", 3) || !strncmp(up, "LPT", 3))) return 0;
        }
        if (j >= len) break;
        i = j + 1;
    }
    return 1;
}

/* ---------------------------------------------------------------- manifest model */
static void free_archive_contents(qzra_archive *a) {
    if (!a) return;
    for (size_t i = 0; i < a->nentries; i++) { free(a->entries[i].path); free(a->entries[i].chunks); }
    free(a->entries);
    for (size_t i = 0; i < a->nscopes; i++) { free(a->scopes[i].path); OPENSSL_cleanse(a->scopes[i].key, sizeof a->scopes[i].key); }
    free(a->scopes);
    free(a->view);
    OPENSSL_cleanse(a->root_key, sizeof a->root_key);
}

void qzra_close(qzra_archive *a) {
    if (!a) return;
    if (a->fp) fclose(a->fp);
    free_archive_contents(a);
    free(a);
}

static const qj_value *need(const qj_value *o, const char *key, qj_type t) {
    const qj_value *v = qj_get(o, key);
    return v && v->type == t ? v : NULL;
}

#define BAD(...) return fail(err, QZRA_ERR_INVALID_FORMAT, __VA_ARGS__)

static qzra_status parse_kdf(const qj_value *k, uint8_t salt_out[16], const char **hex_out, qzra_error *err) {
    const qj_value *name = need(k, "name", QJ_STRING), *n = need(k, "n", QJ_INT), *r = need(k, "r", QJ_INT),
                   *p = need(k, "p", QJ_INT), *salt = need(k, "salt", QJ_STRING);
    if (!name || !n || !r || !p || !salt) BAD("Malformed key-derivation parameters");
    if (strcmp(name->string, "scrypt") || n->integer != 32768 || r->integer != 8 || p->integer != 1 || hex_decode(salt->string, 32, salt_out))
        BAD("Unsupported QZRA key-derivation parameters");
    if (hex_out) *hex_out = salt->string;
    return QZRA_OK;
}

static qzra_status parse_chunk(const qj_value *c, chunk_t *out, int minor, uint64_t payload_len, qzra_error *err, char scope_hex[33], int *has_scope) {
    const qj_value *off = need(c, "offset", QJ_INT), *cs = need(c, "compressed_size", QJ_INT), *sz = need(c, "size", QJ_INT),
                   *comp = need(c, "compression", QJ_STRING), *sha = need(c, "sha256", QJ_STRING);
    if (!off || !cs || !sz || !comp || !sha) BAD("Malformed chunk descriptor");
    if (off->integer < 0 || cs->integer <= 0 || sz->integer <= 0) BAD("Invalid block bounds");
    out->offset = (uint64_t)off->integer; out->csize = (uint64_t)cs->integer; out->size = (uint64_t)sz->integer;
    if (!strcmp(comp->string, "zlib")) out->zlib = 1; else if (!strcmp(comp->string, "store")) out->zlib = 0; else BAD("Unsupported compression method");
    if (hex_decode(sha->string, 64, out->sha)) BAD("Invalid block digest");
    const qj_value *nonce = qj_get(c, "nonce"), *scope = qj_get(c, "scope_id");
    out->has_nonce = 0; *has_scope = 0;
    if (nonce && nonce->type != QJ_NULL) {
        if (nonce->type != QJ_STRING || hex_decode(nonce->string, 24, out->nonce)) BAD("Malformed AES-GCM nonce");
        out->has_nonce = 1;
    }
    if (scope && scope->type != QJ_NULL) {
        if (scope->type != QJ_STRING || strlen(scope->string) != 32) BAD("Malformed QZRA 1.3 chunk scope");
        uint8_t tmp[16];
        if (hex_decode(scope->string, 32, tmp)) BAD("Malformed QZRA 1.3 chunk scope");
        memcpy(scope_hex, scope->string, 33); *has_scope = 1;
    }
    if (minor >= 2) { if (!out->has_nonce) BAD("Encrypted QZRA chunk is missing a valid AES-GCM nonce"); }
    else if (out->has_nonce || *has_scope) BAD("AES-GCM metadata requires QZRA 1.2+");
    if (minor != 3 && *has_scope) BAD("Folder-scoped chunks require QZRA 1.3");
    uint64_t maxo = MAX_CHUNK;
    if (out->size > maxo || out->csize > maxo + 65536u) BAD("Block exceeds the supported size limit");
    if (!out->zlib && out->csize != out->size + (out->has_nonce ? GCM_TAG : 0)) BAD("Stored block size mismatch");
    if (out->offset > payload_len || out->csize > payload_len - out->offset) BAD("Block extends beyond payload");
    return QZRA_OK;
}

static int cmp_chunk_ptr(const void *a, const void *b) {
    const chunk_t *x = *(chunk_t *const *)a, *y = *(chunk_t *const *)b;
    if (x->offset != y->offset) return x->offset < y->offset ? -1 : 1;
    if (x->csize != y->csize) return x->csize < y->csize ? -1 : 1;
    return 0;
}
static int cmp_entry_path(const void *a, const void *b) { return strcmp((*(entry_t *const *)a)->path, (*(entry_t *const *)b)->path); }

static char *ascii_lower_dup(const char *s) {
    size_t n = strlen(s);
    char *d = malloc(n + 1);
    if (!d) return NULL;
    for (size_t i = 0; i <= n; i++) d[i] = (char)((s[i] >= 'A' && s[i] <= 'Z') ? s[i] + 32 : s[i]);
    return d;
}
static int cmp_str_ptr(const void *a, const void *b) { return strcmp(*(char *const *)a, *(char *const *)b); }

static qzra_status build_model(qzra_archive *a, const qj_value *m, int minor, uint32_t header_count, const uint8_t *env_salt, qzra_error *err) {
    const qj_value *fmt = need(m, "format", QJ_STRING), *ver = need(m, "version", QJ_STRING), *ents = need(m, "entries", QJ_ARRAY);
    char want[8];
    snprintf(want, sizeof want, "1.%d", minor);
    if (!fmt || !ver || !ents || strcmp(fmt->string, "QZRA") || strcmp(ver->string, want)) BAD("Manifest/header version mismatch");
    if (ents->count > MAX_ENTRIES) BAD("Too many manifest entries");
    if (minor != 3 && ents->count != header_count) BAD("Manifest/header entry count mismatch");

    const qj_value *sec = qj_get(m, "security"), *auth = qj_get(m, "manifest_auth");
    if (minor < 2 && ((sec && sec->type != QJ_NULL) || (auth && auth->type != QJ_NULL))) BAD("Unencrypted QZRA archives cannot contain encryption metadata");
    if (minor == 3) {
        const qj_value *enc = sec ? need(sec, "encryption", QJ_STRING) : NULL, *menc = sec ? need(sec, "manifest_encryption", QJ_STRING) : NULL;
        const qj_value *kdf = sec ? need(sec, "kdf", QJ_OBJECT) : NULL, *scopes = sec ? need(sec, "scopes", QJ_ARRAY) : NULL;
        if (!enc || !menc || !kdf || !scopes || strcmp(enc->string, "AES-256-GCM") || strcmp(menc->string, "AES-256-GCM")) BAD("Unsupported QZRA 1.3 encryption configuration");
        uint8_t ksalt[16]; const char *khex = NULL;
        qzra_status st = parse_kdf(kdf, ksalt, &khex, err);
        if (st) return st;
        if (memcmp(ksalt, env_salt, 16)) BAD("Invalid QZRA 1.3 KDF metadata");
        if (scopes->count > MAX_SCOPES) BAD("Too many QZRA 1.3 protected folders");
        if (scopes->count) {
            a->scopes = calloc(scopes->count, sizeof *a->scopes);
            if (!a->scopes) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
        }
        for (size_t i = 0; i < scopes->count; i++) {
            const qj_value *s = scopes->items[i];
            const qj_value *id = need(s, "id", QJ_STRING), *path = need(s, "path", QJ_STRING), *sk = need(s, "kdf", QJ_OBJECT), *chk = need(s, "key_check", QJ_OBJECT);
            if (!id || !path || !sk || !chk) BAD("Malformed QZRA 1.3 folder scope");
            scope_t *sc = &a->scopes[i];
            a->nscopes = i + 1;
            if (hex_decode(id->string, 32, sc->id)) BAD("Duplicate or malformed QZRA 1.3 scope");
            memcpy(sc->id_hex, id->string, 33);
            if (!path_is_safe(path->string, path->string_len)) return fail(err, QZRA_ERR_UNSAFE_PATH, "Unsafe protected-folder path");
            sc->path = strdup(path->string);
            if (!sc->path) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
            for (size_t j = 0; j < i; j++) if (!strcmp(a->scopes[j].id_hex, sc->id_hex) || !strcmp(a->scopes[j].path, sc->path)) BAD("Duplicate or malformed QZRA 1.3 scope");
            if ((st = parse_kdf(sk, sc->salt, NULL, err))) return st;
            const qj_value *cn = need(chk, "nonce", QJ_STRING), *ct = need(chk, "tag", QJ_STRING);
            if (!cn || !ct || hex_decode(cn->string, 24, sc->check_nonce) || hex_decode(ct->string, 32, sc->check_tag)) BAD("Invalid folder key-check record");
        }
    }

    /* entries */
    a->nentries = 0;
    if (ents->count) {
        a->entries = calloc(ents->count, sizeof *a->entries);
        if (!a->entries) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
    }
    size_t total_chunks = 0;
    for (size_t i = 0; i < ents->count; i++) {
        const qj_value *e = ents->items[i];
        const qj_value *path = need(e, "path", QJ_STRING), *type = need(e, "type", QJ_STRING), *size = need(e, "size", QJ_INT);
        if (!path || !type || !size) BAD("Malformed manifest entry");
        entry_t *en = &a->entries[i];
        a->nentries = i + 1;
        if (!path_is_safe(path->string, path->string_len)) return fail(err, QZRA_ERR_UNSAFE_PATH, "Unsafe archive path in manifest");
        en->path = strdup(path->string);
        if (!en->path) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
        if (size->integer < 0) BAD("Negative file size");
        en->size = (uint64_t)size->integer;
        if (!strcmp(type->string, "directory")) {
            en->is_dir = 1;
            if (en->size != 0 || qj_get(e, "chunks")) BAD("Invalid directory entry");
            continue;
        }
        if (strcmp(type->string, "file")) BAD("Unsupported entry type");
        const qj_value *sha = need(e, "sha256", QJ_STRING);
        if (!sha || hex_decode(sha->string, 64, en->sha)) BAD("Missing or invalid file digest");
        if (minor == 0) {
            if (qj_get(e, "chunks")) BAD("QZRA 1.0 entries cannot contain chunks");
            const qj_value *off = need(e, "offset", QJ_INT), *cs = need(e, "compressed_size", QJ_INT), *comp = need(e, "compression", QJ_STRING);
            if (!off || !cs || !comp) BAD("Missing legacy block fields");
            if (en->size == 0) {
                if (cs->integer != 0 || strcmp(comp->string, "store") || off->integer < 0 || (uint64_t)off->integer > a->payload_len) BAD("Invalid empty legacy file");
            } else {
                en->chunks = calloc(1, sizeof *en->chunks);
                if (!en->chunks) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
                en->nchunks = 1;
                chunk_t *c = en->chunks;
                if (off->integer < 0 || cs->integer <= 0) BAD("Invalid block bounds");
                c->offset = (uint64_t)off->integer; c->csize = (uint64_t)cs->integer; c->size = en->size;
                if (!strcmp(comp->string, "zlib")) c->zlib = 1; else if (!strcmp(comp->string, "store")) c->zlib = 0; else BAD("Unsupported compression method");
                memcpy(c->sha, en->sha, 32);
                if (c->size > MAX_LEGACY_BLOCK || c->csize > MAX_LEGACY_BLOCK + 65536u) BAD("Block exceeds the supported size limit");
                if (!c->zlib && c->csize != c->size) BAD("Stored block size mismatch");
                if (c->offset > a->payload_len || c->csize > a->payload_len - c->offset) BAD("Block extends beyond payload");
                total_chunks++;
            }
        } else {
            const qj_value *chunks = need(e, "chunks", QJ_ARRAY);
            if (!chunks) BAD("Chunked QZRA entries must contain chunks");
            if ((en->size == 0) != (chunks->count == 0)) BAD("Invalid empty/non-empty chunk list");
            if (chunks->count > 0x7fffffff) BAD("Too many chunks");
            if (chunks->count) {
                en->chunks = calloc(chunks->count, sizeof *en->chunks);
                if (!en->chunks) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
            }
            en->nchunks = chunks->count;
            uint64_t sum = 0;
            for (size_t k = 0; k < chunks->count; k++) {
                char shex[33]; int has_scope = 0;
                qzra_status st = parse_chunk(chunks->items[k], &en->chunks[k], minor, a->payload_len, err, shex, &has_scope);
                if (st) return st;
                if (has_scope) {
                    size_t found = 0;
                    for (size_t s = 0; s < a->nscopes; s++) if (!strcmp(a->scopes[s].id_hex, shex)) found = s + 1;
                    if (!found) BAD("Chunk references an unknown protected-folder scope");
                    en->chunks[k].scope = found;
                }
                if (en->chunks[k].size > UINT64_MAX - sum) BAD("Chunk sizes overflow");
                sum += en->chunks[k].size;
                total_chunks++;
            }
            if (sum != en->size) BAD("Chunk sizes do not match file size");
        }
    }

    /* uniqueness, case collisions, parent/child consistency */
    size_t n = a->nentries;
    if (n) {
        char **lower = calloc(n, sizeof *lower);
        entry_t **sorted = malloc(n * sizeof *sorted);
        if (!lower || !sorted) { free(lower); free(sorted); return fail(err, QZRA_ERR_NO_MEMORY, "out of memory"); }
        qzra_status result = QZRA_OK;
        for (size_t i = 0; i < n && result == QZRA_OK; i++) {
            lower[i] = ascii_lower_dup(a->entries[i].path);
            sorted[i] = &a->entries[i];
            if (!lower[i]) result = fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
        }
        if (result == QZRA_OK) {
            qsort(lower, n, sizeof *lower, cmp_str_ptr);
            for (size_t i = 1; i < n; i++) if (!strcmp(lower[i - 1], lower[i])) { result = fail(err, QZRA_ERR_INVALID_FORMAT, "Duplicate or case-insensitive colliding archive path"); break; }
        }
        if (result == QZRA_OK) {
            qsort(sorted, n, sizeof *sorted, cmp_entry_path);
            for (size_t i = 0; i < n && result == QZRA_OK; i++) {
                const char *p = a->entries[i].path;
                for (const char *slash = strchr(p, '/'); slash && result == QZRA_OK; slash = strchr(slash + 1, '/')) {
                    size_t plen = (size_t)(slash - p);
                    char *prefix = malloc(plen + 1);
                    if (!prefix) { result = fail(err, QZRA_ERR_NO_MEMORY, "out of memory"); break; }
                    memcpy(prefix, p, plen); prefix[plen] = '\0';
                    entry_t key = { .path = prefix }, *kp = &key;
                    entry_t **hit = bsearch(&kp, sorted, n, sizeof *sorted, cmp_entry_path);
                    if (hit && !(*hit)->is_dir) result = fail(err, QZRA_ERR_INVALID_FORMAT, "File entry is also a parent directory");
                    free(prefix);
                }
            }
        }
        for (size_t i = 0; i < n; i++) free(lower[i]);
        free(lower); free(sorted);
        if (result) return result;
    }

    /* protected-folder scoping (QZRA 1.3) */
    if (minor == 3) {
        for (size_t s = 0; s < a->nscopes; s++) {
            int found = 0;
            for (size_t i = 0; i < a->nentries; i++) if (a->entries[i].is_dir && !strcmp(a->entries[i].path, a->scopes[s].path)) { found = 1; break; }
            if (!found) BAD("Protected folder is not declared as a directory");
        }
        for (size_t i = 0; i < a->nentries; i++) {
            entry_t *en = &a->entries[i];
            size_t best = 0, bestlen = 0;
            for (size_t s = 0; s < a->nscopes; s++) {
                size_t pl = strlen(a->scopes[s].path);
                if (!strncmp(en->path, a->scopes[s].path, pl) && en->path[pl] == '/' && pl >= bestlen) { best = s + 1; bestlen = pl; }
            }
            en->scope = best;
            if (!en->is_dir) for (size_t k = 0; k < en->nchunks; k++)
                if (en->chunks[k].scope != best) BAD("QZRA 1.3 chunk scope does not match its protected folder");
        }
    }

    /* payload layout: unique blocks must tile the payload exactly */
    chunk_t **all = NULL;
    if (total_chunks) {
        all = malloc(total_chunks * sizeof *all);
        if (!all) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
    }
    size_t w = 0;
    for (size_t i = 0; i < a->nentries; i++) for (size_t k = 0; k < a->entries[i].nchunks; k++) all[w++] = &a->entries[i].chunks[k];
    qsort(all, total_chunks, sizeof *all, cmp_chunk_ptr);
    uint64_t cursor = 0;
    qzra_status result = QZRA_OK;
    for (size_t i = 0; i < total_chunks; i++) {
        const chunk_t *c = all[i];
        if (i && all[i - 1]->offset == c->offset && all[i - 1]->csize == c->csize) {
            const chunk_t *p = all[i - 1];
            if (p->size != c->size || p->zlib != c->zlib || memcmp(p->sha, c->sha, 32) || p->has_nonce != c->has_nonce ||
                (c->has_nonce && memcmp(p->nonce, c->nonce, 12)) || p->scope != c->scope) {
                result = fail(err, QZRA_ERR_INVALID_FORMAT, "Inconsistent shared chunk reference");
                break;
            }
            continue;
        }
        if (c->offset != cursor) { result = fail(err, QZRA_ERR_INVALID_FORMAT, "Payload contains a gap or overlapping block"); break; }
        cursor += c->csize;
    }
    free(all);
    if (result) return result;
    if (cursor != a->payload_len) BAD("Payload has unreferenced trailing bytes");
    return QZRA_OK;
}

/* ---------------------------------------------------------------- open */
qzra_status qzra_open(const char *path, const char *password, qzra_archive **out, qzra_error *err) {
    if (out) *out = NULL;
    if (!path || !out) return fail(err, QZRA_ERR_INVALID_ARGUMENT, "path and out must not be NULL");
    if (password && !*password) return fail(err, QZRA_ERR_INVALID_ARGUMENT, "Password must not be empty");
    qzra_archive *a = calloc(1, sizeof *a);
    if (!a) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
    qzra_status st = QZRA_OK;
    uint8_t *raw = NULL, *plain = NULL;
    qj_value *root = NULL;
    char *canon = NULL;
    uint8_t master[32] = {0}, mkey[32] = {0}, key12[32] = {0};

    a->fp = fopen(path, "rb");
    if (!a->fp) { st = fail(err, QZRA_ERR_IO, "Cannot open archive: %s", strerror(errno)); goto done; }
    if (fseeko(a->fp, 0, SEEK_END) != 0) { st = fail(err, QZRA_ERR_IO, "Cannot determine archive size"); goto done; }
    off_t sz = ftello(a->fp);
    if (sz < 0) { st = fail(err, QZRA_ERR_IO, "Cannot determine archive size"); goto done; }
    a->file_len = (uint64_t)sz;
    if (a->file_len < HEADER_SIZE) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "File is too small to be QZRA"); goto done; }
    uint8_t hdr[HEADER_SIZE];
    if (file_seek(a->fp, 0) || fread(hdr, 1, HEADER_SIZE, a->fp) != HEADER_SIZE) { st = fail(err, QZRA_ERR_IO, "Cannot read header"); goto done; }
    static const uint8_t magic[8] = { 0x51, 0x5a, 0x52, 0x41, 0x0d, 0x0a, 0x1a, 0x0a };
    if (memcmp(hdr, magic, 8)) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Invalid QZRA signature"); goto done; }
    unsigned major = be16(hdr + 8), minor = be16(hdr + 10);
    if (major != 1 || minor > 3) { st = fail(err, QZRA_ERR_UNSUPPORTED_VERSION, "Unsupported QZRA version %u.%u", major, minor); goto done; }
    uint32_t flags = be32(hdr + 12), count = be32(hdr + 24), reserved = be32(hdr + 28);
    uint64_t mlen = be64(hdr + 16);
    if (flags != 0 || reserved != 0) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Unsupported QZRA header flags/reserved field"); goto done; }
    uint64_t maxm = (uint64_t)MAX_MANIFEST + (minor == 3 ? 44u : 0u);
    if (mlen < 2 || mlen > maxm || count > MAX_ENTRIES) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Invalid manifest bounds"); goto done; }
    if (HEADER_SIZE + mlen > a->file_len) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Manifest extends beyond file"); goto done; }
    a->minor = (int)minor;
    a->payload_start = HEADER_SIZE + mlen;
    a->payload_len = a->file_len - a->payload_start;
    raw = malloc((size_t)mlen);
    if (!raw) { st = fail(err, QZRA_ERR_NO_MEMORY, "out of memory"); goto done; }
    if (fread(raw, 1, (size_t)mlen, a->fp) != (size_t)mlen) { st = fail(err, QZRA_ERR_IO, "Cannot read manifest"); goto done; }

    const uint8_t *text = raw; size_t tlen = (size_t)mlen;
    uint8_t env_salt[16] = {0};
    if (minor >= 2 && !password) { st = fail(err, QZRA_ERR_PASSWORD_REQUIRED, "This QZRA archive is encrypted; a password is required"); goto done; }
    if (minor == 3) {
        if (count != 0) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "QZRA 1.3 must hide the manifest entry count"); goto done; }
        if (mlen < 4 + 16 + 12 + 16 || memcmp(raw, "QZ13", 4)) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Invalid QZRA 1.3 encrypted manifest envelope"); goto done; }
        memcpy(env_salt, raw + 4, 16);
        const uint8_t *nonce = raw + 20;
        if (scrypt_key(password, env_salt, master) || subkey(master, "root-chunks", a->root_key) || subkey(master, "manifest", mkey)) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Key derivation failed"); goto done; }
        a->has_root_key = 1;
        uint8_t aad[18 + 2 + 2 + 4 + 4 + 16 + 12];
        static const char aadp[] = "QZRA-1.3-MANIFEST"; /* + NUL */
        size_t o = 0;
        memcpy(aad, aadp, sizeof aadp); o += sizeof aadp;
        aad[o++] = 0; aad[o++] = 1; aad[o++] = 0; aad[o++] = 3;
        memset(aad + o, 0, 8); o += 8;  /* flags and reserved are both zero (checked above) */
        memcpy(aad + o, env_salt, 16); o += 16;
        memcpy(aad + o, nonce, 12); o += 12;
        size_t ctlen = (size_t)mlen - 32;
        plain = malloc(ctlen);
        if (!plain) { st = fail(err, QZRA_ERR_NO_MEMORY, "out of memory"); goto done; }
        int rc = aead_decrypt(mkey, nonce, aad, o, raw + 32, ctlen, plain);
        if (rc == -1) { st = fail(err, QZRA_ERR_AUTHENTICATION_FAILED, "Wrong password or encrypted archive authentication failed"); goto done; }
        if (rc) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Cannot decrypt manifest"); goto done; }
        text = plain; tlen = ctlen - GCM_TAG;
        if (tlen > MAX_MANIFEST) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Decrypted QZRA 1.3 manifest exceeds the size limit"); goto done; }
    }

    char perr[128];
    if (qj_parse((const char *)text, tlen, &root, perr, sizeof perr)) {
        st = fail(err, strstr(perr, "memory") ? QZRA_ERR_NO_MEMORY : (strstr(perr, "too many") ? QZRA_ERR_LIMIT : QZRA_ERR_INVALID_FORMAT), "Invalid manifest: %s", perr);
        goto done;
    }
    if (root->type != QJ_OBJECT) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Manifest is not a JSON object"); goto done; }

    if (minor == 2) {
        const qj_value *sec = qj_get(root, "security"), *auth = qj_get(root, "manifest_auth");
        const qj_value *enc = sec ? need(sec, "encryption", QJ_STRING) : NULL, *kdf = sec ? need(sec, "kdf", QJ_OBJECT) : NULL;
        if (!sec || !auth || !enc || !kdf || strcmp(enc->string, "AES-256-GCM")) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "QZRA 1.2 security metadata is missing or unsupported"); goto done; }
        uint8_t salt[16], anonce[12], atag[16];
        if ((st = parse_kdf(kdf, salt, NULL, err))) goto done;
        const qj_value *an = need(auth, "nonce", QJ_STRING), *at = need(auth, "tag", QJ_STRING);
        if (!an || !at || hex_decode(an->string, 24, anonce) || hex_decode(at->string, 32, atag)) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Malformed QZRA manifest authenticator"); goto done; }
        size_t clen = 0;
        if (qj_canonical(root, "manifest_auth", &canon, &clen)) { st = fail(err, QZRA_ERR_NO_MEMORY, "out of memory"); goto done; }
        if (scrypt_key(password, salt, key12)) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Key derivation failed"); goto done; }
        int rc = aead_decrypt(key12, anonce, (const uint8_t *)canon, clen, atag, sizeof atag, NULL);
        if (rc == -1) { st = fail(err, QZRA_ERR_AUTHENTICATION_FAILED, "Wrong password or QZRA manifest authentication failed"); goto done; }
        if (rc) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Cannot authenticate manifest"); goto done; }
        memcpy(a->root_key, key12, 32); a->has_root_key = 1;
    }
    if ((st = build_model(a, root, (int)minor, count, env_salt, err))) goto done;

    a->view = calloc(a->nentries ? a->nentries : 1, sizeof *a->view);
    if (!a->view) { st = fail(err, QZRA_ERR_NO_MEMORY, "out of memory"); goto done; }
    for (size_t i = 0; i < a->nentries; i++) {
        a->view[i].path = a->entries[i].path; a->view[i].is_directory = a->entries[i].is_dir; a->view[i].size = a->entries[i].size;
    }
    *out = a;
    a = NULL;
done:
    OPENSSL_cleanse(master, sizeof master); OPENSSL_cleanse(mkey, sizeof mkey); OPENSSL_cleanse(key12, sizeof key12);
    free(raw); free(plain); free(canon); qj_free(root);
    if (a) qzra_close(a);
    return st;
}

qzra_status qzra_unlock_folder(qzra_archive *a, const char *folder_path, const char *password, qzra_error *err) {
    if (!a || !folder_path || !password || !*password) return fail(err, QZRA_ERR_INVALID_ARGUMENT, "archive, folder_path and a non-empty password are required");
    if (a->minor != 3) return fail(err, QZRA_ERR_INVALID_ARGUMENT, "Only QZRA 1.3 archives have protected folders");
    scope_t *sc = NULL;
    for (size_t i = 0; i < a->nscopes; i++) if (!strcmp(a->scopes[i].path, folder_path)) sc = &a->scopes[i];
    if (!sc) return fail(err, QZRA_ERR_INVALID_ARGUMENT, "No protected folder exists at: %s", folder_path);
    uint8_t master[32], check[32], payload[32];
    qzra_status st = QZRA_OK;
    if (scrypt_key(password, sc->salt, master) || subkey(master, "folder-check", check) || subkey(master, "folder-chunks", payload)) {
        st = fail(err, QZRA_ERR_INVALID_FORMAT, "Key derivation failed");
    } else {
        size_t plen = strlen(sc->path);
        uint8_t *aad = malloc(21 + 16 + plen);
        if (!aad) st = fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
        else {
            static const char p[] = "QZRA-1.3-SCOPE-CHECK"; /* + NUL */
            memcpy(aad, p, sizeof p);
            memcpy(aad + sizeof p, sc->id, 16);
            memcpy(aad + sizeof p + 16, sc->path, plen);
            int rc = aead_decrypt(check, sc->check_nonce, aad, sizeof p + 16 + plen, sc->check_tag, 16, NULL);
            free(aad);
            if (rc == -1) st = fail(err, QZRA_ERR_AUTHENTICATION_FAILED, "Wrong password for protected folder: %s", folder_path);
            else if (rc) st = fail(err, QZRA_ERR_INVALID_FORMAT, "Cannot check folder password");
            else { memcpy(sc->key, payload, 32); sc->unlocked = 1; }
        }
    }
    OPENSSL_cleanse(master, 32); OPENSSL_cleanse(check, 32); OPENSSL_cleanse(payload, 32);
    return st;
}

int qzra_version_minor(const qzra_archive *a) { return a ? a->minor : -1; }
size_t qzra_entry_count(const qzra_archive *a) { return a ? a->nentries : 0; }
qzra_status qzra_entry_at(const qzra_archive *a, size_t i, qzra_entry *out) {
    if (!a || !out || i >= a->nentries) return QZRA_ERR_INVALID_ARGUMENT;
    *out = a->view[i];
    return QZRA_OK;
}

/* ---------------------------------------------------------------- decoding */
static qzra_status check_scope_unlocked(const qzra_archive *a, const entry_t *en, qzra_error *err) {
    if (en->scope && !a->scopes[en->scope - 1].unlocked)
        return fail(err, QZRA_ERR_FOLDER_LOCKED, "Folder is locked; unlock it with its independent password: %s", a->scopes[en->scope - 1].path);
    return QZRA_OK;
}

static qzra_status decode_chunk(qzra_archive *a, const chunk_t *c, uint8_t *raw_out, qzra_error *err) {
    size_t csize = (size_t)c->csize;
    uint8_t *enc = malloc(csize ? csize : 1), *comp_buf = NULL;
    if (!enc) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
    qzra_status st = QZRA_OK;
    if (file_seek(a->fp, a->payload_start + c->offset) || fread(enc, 1, csize, a->fp) != csize) { st = fail(err, QZRA_ERR_IO, "Cannot read chunk"); goto done; }
    const uint8_t *comp = enc; size_t comp_len = csize;
    if (c->has_nonce) {
        const uint8_t *key = c->scope ? a->scopes[c->scope - 1].key : a->root_key;
        if (!c->scope && !a->has_root_key) { st = fail(err, QZRA_ERR_PASSWORD_REQUIRED, "Encrypted chunk without a key"); goto done; }
        const char *cname = c->zlib ? "zlib" : "store";
        uint8_t aad[16 + 8 + 32 + 4 + 1 + 32];
        size_t o = 0;
        if (a->minor == 3) { static const char p[] = "QZRA-1.3-CHUNK"; memcpy(aad, p, sizeof p); o = sizeof p; }
        else { static const char p[] = "QZRA-1.2-CHUNK"; memcpy(aad, p, sizeof p); o = sizeof p; }
        put_be64(aad + o, c->size); o += 8;
        memcpy(aad + o, c->sha, 32); o += 32;
        memcpy(aad + o, cname, strlen(cname)); o += strlen(cname);
        if (a->minor == 3) {
            aad[o++] = 0;
            if (c->scope) { memcpy(aad + o, a->scopes[c->scope - 1].id_hex, 32); o += 32; } else aad[o++] = '-';
        }
        comp_buf = malloc(csize);
        if (!comp_buf) { st = fail(err, QZRA_ERR_NO_MEMORY, "out of memory"); goto done; }
        int rc = aead_decrypt(key, c->nonce, aad, o, enc, csize, comp_buf);
        if (rc == -1) { st = fail(err, QZRA_ERR_AUTHENTICATION_FAILED, "Wrong password or encrypted archive authentication failed"); goto done; }
        if (rc) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Cannot decrypt chunk"); goto done; }
        comp = comp_buf; comp_len = csize - GCM_TAG;
    } else if (a->minor >= 2) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "Encrypted archive contains an unauthenticated chunk"); goto done; }

    if (!c->zlib) {
        if (comp_len != c->size) { st = fail(err, QZRA_ERR_INTEGRITY_FAILED, "Stored block size mismatch"); goto done; }
        memcpy(raw_out, comp, comp_len);
    } else {
        z_stream zs; memset(&zs, 0, sizeof zs);
        if (inflateInit(&zs) != Z_OK) { st = fail(err, QZRA_ERR_NO_MEMORY, "zlib initialisation failed"); goto done; }
        zs.next_in = (Bytef *)(uintptr_t)comp; zs.avail_in = (uInt)comp_len;
        zs.next_out = raw_out; zs.avail_out = (uInt)c->size;
        int zr = inflate(&zs, Z_FINISH);
        uLong produced = zs.total_out;
        inflateEnd(&zs);
        if (zr != Z_STREAM_END || produced != c->size || zs.avail_in != 0) { st = fail(err, QZRA_ERR_INTEGRITY_FAILED, "Chunk decompression failed or has the wrong size"); goto done; }
    }
    uint8_t digest[32];
    if (sha256_buf(raw_out, (size_t)c->size, digest)) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "SHA-256 failed"); goto done; }
    if (CRYPTO_memcmp(digest, c->sha, 32)) st = fail(err, QZRA_ERR_INTEGRITY_FAILED, "Chunk SHA-256 mismatch");
done:
    free(enc); free(comp_buf);
    return st;
}

/* Decodes one file; when `out` is non-NULL the plaintext is written to it. */
static qzra_status decode_file(qzra_archive *a, const entry_t *en, FILE *out, uint64_t *bytes, qzra_error *err) {
    qzra_status st = check_scope_unlocked(a, en, err);
    if (st) return st;
    EVP_MD_CTX *md = EVP_MD_CTX_new();
    if (!md || EVP_DigestInit_ex(md, EVP_sha256(), NULL) != 1) { EVP_MD_CTX_free(md); return fail(err, QZRA_ERR_NO_MEMORY, "digest init failed"); }
    uint8_t *buf = malloc(MAX_LEGACY_BLOCK);
    if (!buf) { EVP_MD_CTX_free(md); return fail(err, QZRA_ERR_NO_MEMORY, "out of memory"); }
    uint64_t total = 0;
    for (size_t k = 0; k < en->nchunks && !st; k++) {
        const chunk_t *c = &en->chunks[k];
        if ((st = decode_chunk(a, c, buf, err))) break;
        if (EVP_DigestUpdate(md, buf, (size_t)c->size) != 1) { st = fail(err, QZRA_ERR_INVALID_FORMAT, "digest update failed"); break; }
        if (out && fwrite(buf, 1, (size_t)c->size, out) != (size_t)c->size) { st = fail(err, QZRA_ERR_IO, "Write failed: %s", strerror(errno)); break; }
        total += c->size;
        if (total > en->size) { st = fail(err, QZRA_ERR_INTEGRITY_FAILED, "Expanded file exceeds declared size"); break; }
    }
    if (!st) {
        uint8_t digest[32]; unsigned int dl = 0;
        if (EVP_DigestFinal_ex(md, digest, &dl) != 1 || dl != 32) st = fail(err, QZRA_ERR_INVALID_FORMAT, "digest finalisation failed");
        else if (total != en->size || CRYPTO_memcmp(digest, en->sha, 32)) st = fail(err, QZRA_ERR_INTEGRITY_FAILED, "File integrity check failed: %s", en->path);
    }
    free(buf); EVP_MD_CTX_free(md);
    if (!st && bytes) *bytes += total;
    return st;
}

qzra_status qzra_verify(qzra_archive *a, qzra_verify_result *res, qzra_error *err) {
    if (!a) return fail(err, QZRA_ERR_INVALID_ARGUMENT, "archive must not be NULL");
    qzra_verify_result r = { 0, 0, a->minor };
    for (size_t i = 0; i < a->nentries; i++) {
        const entry_t *en = &a->entries[i];
        if (en->is_dir) continue;
        qzra_status st = decode_file(a, en, NULL, &r.bytes_checked, err);
        if (st) return st;
        r.files_checked++;
    }
    if (res) *res = r;
    return QZRA_OK;
}

/* ---------------------------------------------------------------- extraction */
static int is_symlink(const char *path) {
    struct stat sb;
    return lstat(path, &sb) == 0 && S_ISLNK(sb.st_mode);
}

/* Creates every directory of `rel` (an archive-relative path) below `root`; refuses symlinks. */
static qzra_status make_dirs(const char *root, const char *rel, int include_last, qzra_error *err) {
    size_t rl = strlen(root), n = strlen(rel);
    char *buf = malloc(rl + n + 2);
    if (!buf) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
    memcpy(buf, root, rl); buf[rl] = '/';
    qzra_status st = QZRA_OK;
    for (size_t i = 0; i <= n && !st; i++) {
        if (rel[i] != '/' && rel[i] != '\0') continue;
        if (rel[i] == '\0' && !include_last) break;
        memcpy(buf + rl + 1, rel, i); buf[rl + 1 + i] = '\0';
        if (is_symlink(buf)) { st = fail(err, QZRA_ERR_UNSAFE_PATH, "Refusing to write through a symbolic link: %s", buf); break; }
        if (mkdir(buf, 0777) != 0 && errno != EEXIST) st = fail(err, QZRA_ERR_IO, "Cannot create directory %s: %s", buf, strerror(errno));
        else { struct stat sb; if (stat(buf, &sb) != 0 || !S_ISDIR(sb.st_mode)) st = fail(err, QZRA_ERR_IO, "Not a directory: %s", buf); }
    }
    free(buf);
    return st;
}

qzra_status qzra_extract(qzra_archive *a, const char *dest, int overwrite, qzra_error *err) {
    if (!a || !dest || !*dest) return fail(err, QZRA_ERR_INVALID_ARGUMENT, "archive and destination are required");
    for (size_t i = 0; i < a->nentries; i++) {          /* refuse before writing anything */
        if (a->entries[i].is_dir) continue;
        qzra_status s = check_scope_unlocked(a, &a->entries[i], err);
        if (s) return s;
    }
    char *root = strdup(dest);
    if (!root) return fail(err, QZRA_ERR_NO_MEMORY, "out of memory");
    size_t rn = strlen(root);
    while (rn > 1 && root[rn - 1] == '/') root[--rn] = '\0';
    qzra_status st = QZRA_OK;
    if (is_symlink(root)) { free(root); return fail(err, QZRA_ERR_UNSAFE_PATH, "Destination is a symbolic link"); }
    if (mkdir(root, 0777) != 0 && errno != EEXIST) { st = fail(err, QZRA_ERR_IO, "Cannot create destination: %s", strerror(errno)); free(root); return st; }
    for (size_t i = 0; i < a->nentries && !st; i++) {
        const entry_t *en = &a->entries[i];
        if (en->is_dir) { st = make_dirs(root, en->path, 1, err); continue; }
        if ((st = make_dirs(root, en->path, 0, err))) break;
        size_t pl = rn + 1 + strlen(en->path);
        char *target = malloc(pl + 1), *part = malloc(pl + 12);
        if (!target || !part) { free(target); free(part); st = fail(err, QZRA_ERR_NO_MEMORY, "out of memory"); break; }
        snprintf(target, pl + 1, "%s/%s", root, en->path);
        snprintf(part, pl + 12, "%s.qzra-part", target);
        struct stat sb;
        if (lstat(target, &sb) == 0) {
            if (S_ISLNK(sb.st_mode)) st = fail(err, QZRA_ERR_UNSAFE_PATH, "Refusing to replace a symbolic link: %s", en->path);
            else if (!overwrite) st = fail(err, QZRA_ERR_EXISTS, "Destination already exists: %s", en->path);
        }
        if (!st) {
            FILE *f = fopen(part, "wb");
            if (!f) st = fail(err, QZRA_ERR_IO, "Cannot create %s: %s", part, strerror(errno));
            else {
                st = decode_file(a, en, f, NULL, err);
                if (fclose(f) != 0 && !st) st = fail(err, QZRA_ERR_IO, "Write failed: %s", strerror(errno));
                if (st) remove(part);
                else if (rename(part, target) != 0) { st = fail(err, QZRA_ERR_IO, "Cannot move %s into place: %s", en->path, strerror(errno)); remove(part); }
            }
        }
        free(target); free(part);
    }
    free(root);
    return st;
}
