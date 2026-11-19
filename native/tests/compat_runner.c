/* QAMZOR Compatibility Suite runner for the Native SDK.
 * Usage: compat_runner <path-to-compat-directory>
 * Exit status 0 only if every vector in manifest.json behaves exactly as specified. */
/* Expose Darwin's POSIX-compatible mkdtemp declaration on macOS CI. */
#define _DARWIN_C_SOURCE 1
#define _POSIX_C_SOURCE 200809L
#define _XOPEN_SOURCE 700
#include "qzra.h"
#include "../src/qzra_json.h"
#include <dirent.h>
#include <ftw.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <openssl/evp.h>

static char *slurp(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *b = malloc((size_t)n + 1);
    if (!b || fread(b, 1, (size_t)n, f) != (size_t)n) { fclose(f); free(b); return NULL; }
    fclose(f);
    b[n] = '\0'; *len = (size_t)n;
    return b;
}

static void hex_sha256(const unsigned char *d, size_t n, char out[65]) {
    unsigned char h[32]; unsigned int hl = 0;
    EVP_Digest(d, n, h, &hl, EVP_sha256(), NULL);
    for (int i = 0; i < 32; i++) snprintf(out + 2 * i, 3, "%02x", h[i]);
}

static int rm_cb(const char *p, const struct stat *sb, int flag, struct FTW *ftw) { (void)sb; (void)flag; (void)ftw; return remove(p); }
static void rmtree(const char *p) { nftw(p, rm_cb, 16, FTW_DEPTH | FTW_PHYS); }

typedef struct { const char *path; int is_dir; uint64_t size; } ent;
static int cmp_ent(const void *a, const void *b) { return strcmp(((const ent *)a)->path, ((const ent *)b)->path); }

/* Returns NULL on success, otherwise a malloc'ed description of the problem. */
static char *run_vector(const char *suite, const qj_value *v) {
    char msg[512], path[4096];
    const char *file = qj_get(v, "file")->string;
    const char *sha = qj_get(v, "sha256")->string;
    snprintf(path, sizeof path, "%s/vectors/%s", suite, file);
    size_t flen = 0;
    char *bytes = slurp(path, &flen);
    if (!bytes) { snprintf(msg, sizeof msg, "cannot read vector file"); return strdup(msg); }
    char hex[65];
    hex_sha256((unsigned char *)bytes, flen, hex);
    free(bytes);
    if (strcmp(hex, sha)) { snprintf(msg, sizeof msg, "vector digest mismatch"); return strdup(msg); }

    const qj_value *expect = qj_get(v, "expect"), *pw = qj_get(v, "password"), *fps = qj_get(v, "folder_passwords");
    int expect_ok = !strcmp(qj_get(expect, "outcome")->string, "ok");
    const char *category = expect_ok ? NULL : qj_get(expect, "category")->string;

    char sandbox[] = "/tmp/qzra-compat-XXXXXX";
    if (!mkdtemp(sandbox)) return strdup("cannot create sandbox");
    char dest[4200];
    snprintf(dest, sizeof dest, "%s/out", sandbox);
    mkdir(dest, 0777);

    qzra_archive *a = NULL;
    qzra_error err; memset(&err, 0, sizeof err);
    qzra_status st = qzra_open(path, pw->type == QJ_STRING ? pw->string : NULL, &a, &err);
    for (size_t i = 0; st == QZRA_OK && i < fps->count; i++)
        st = qzra_unlock_folder(a, fps->keys[i], fps->items[i]->string, &err);
    char *problem = NULL;
    if (st == QZRA_OK) {
        size_t n = qzra_entry_count(a);
        ent *got = calloc(n ? n : 1, sizeof *got);
        for (size_t i = 0; i < n; i++) { qzra_entry e; qzra_entry_at(a, i, &e); got[i].path = e.path; got[i].is_dir = e.is_directory; got[i].size = e.size; }
        qsort(got, n, sizeof *got, cmp_ent);
        if (expect_ok) {
            const qj_value *want = qj_get(expect, "entries");
            if (want->count != n) { snprintf(msg, sizeof msg, "entry count %zu != expected %zu", n, want->count); problem = strdup(msg); }
            for (size_t i = 0; !problem && i < n; i++) {
                const qj_value *w = want->items[i];
                if (strcmp(qj_get(w, "path")->string, got[i].path) || (!strcmp(qj_get(w, "type")->string, "directory")) != got[i].is_dir ||
                    (uint64_t)qj_get(w, "size")->integer != got[i].size) { snprintf(msg, sizeof msg, "entry mismatch at %s", got[i].path); problem = strdup(msg); }
            }
        }
        free(got);
        if (!problem) st = qzra_verify(a, NULL, &err);
        if (!problem && st == QZRA_OK) st = qzra_extract(a, dest, 0, &err);
        if (!problem && st == QZRA_OK && expect_ok) {
            const qj_value *want = qj_get(expect, "entries");
            for (size_t i = 0; !problem && i < want->count; i++) {
                const qj_value *w = want->items[i];
                char target[4400];
                snprintf(target, sizeof target, "%s/%s", dest, qj_get(w, "path")->string);
                struct stat sb;
                if (stat(target, &sb) != 0) { snprintf(msg, sizeof msg, "missing extracted entry %s", qj_get(w, "path")->string); problem = strdup(msg); break; }
                if (!strcmp(qj_get(w, "type")->string, "directory")) { if (!S_ISDIR(sb.st_mode)) problem = strdup("expected directory"); continue; }
                size_t l = 0; char *content = slurp(target, &l);
                if (!content) { problem = strdup("cannot read extracted file"); break; }
                hex_sha256((unsigned char *)content, l, hex);
                free(content);
                if (strcmp(hex, qj_get(w, "sha256")->string)) { snprintf(msg, sizeof msg, "extracted content differs: %s", qj_get(w, "path")->string); problem = strdup(msg); }
            }
        }
    }
    /* anything other than "out" inside the sandbox means a file escaped the destination */
    DIR *d = opendir(sandbox);
    struct dirent *de;
    while (d && (de = readdir(d))) {
        if (strcmp(de->d_name, ".") && strcmp(de->d_name, "..") && strcmp(de->d_name, "out")) {
            snprintf(msg, sizeof msg, "file escaped destination: %s", de->d_name);
            free(problem); problem = strdup(msg);
        }
    }
    if (d) closedir(d);
    if (!problem) {
        if (expect_ok && st != QZRA_OK) { snprintf(msg, sizeof msg, "unexpected %s: %s", qzra_status_name(st), err.message); problem = strdup(msg); }
        else if (!expect_ok && st == QZRA_OK) { snprintf(msg, sizeof msg, "expected %s but the archive was accepted", category); problem = strdup(msg); }
        else if (!expect_ok && strcmp(qzra_status_name(st), category)) { snprintf(msg, sizeof msg, "expected %s, got %s (%s)", category, qzra_status_name(st), err.message); problem = strdup(msg); }
    }
    qzra_close(a);
    rmtree(sandbox);
    return problem;
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: %s <compat-dir>\n", argv[0]); return 2; }
    char mpath[4096];
    snprintf(mpath, sizeof mpath, "%s/manifest.json", argv[1]);
    size_t len = 0;
    char *text = slurp(mpath, &len);
    if (!text) { fprintf(stderr, "cannot read %s\n", mpath); return 2; }
    qj_value *root = NULL; char perr[128];
    if (qj_parse(text, len, &root, perr, sizeof perr)) { fprintf(stderr, "bad manifest.json: %s\n", perr); return 2; }
    const qj_value *vectors = qj_get(root, "vectors");
    printf("libqzra %s\n", qzra_version());
    int failures = 0;
    for (size_t i = 0; i < vectors->count; i++) {
        char *problem = run_vector(argv[1], vectors->items[i]);
        printf("%s %s%s%s\n", problem ? "FAIL" : "PASS", qj_get(vectors->items[i], "id")->string, problem ? "  -- " : "", problem ? problem : "");
        failures += problem != NULL;
        free(problem);
    }
    printf("%zu/%zu vectors passed\n", vectors->count - (size_t)failures, vectors->count);
    qj_free(root); free(text);
    return failures ? 1 : 0;
}
