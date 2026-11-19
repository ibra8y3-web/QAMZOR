#include "qzra_json.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    const unsigned char *p, *end;
    size_t nodes;
    char *err;
    size_t err_size;
} parser;

static int perr(parser *ps, const char *msg) {
    if (ps->err && ps->err_size) snprintf(ps->err, ps->err_size, "%s", msg);
    return -1;
}

static int valid_utf8(const unsigned char *s, size_t n) {
    size_t i = 0;
    while (i < n) {
        unsigned c = s[i];
        if (c < 0x80) { i++; continue; }
        size_t need; uint32_t cp, min;
        if ((c & 0xE0) == 0xC0) { need = 1; cp = c & 0x1F; min = 0x80; }
        else if ((c & 0xF0) == 0xE0) { need = 2; cp = c & 0x0F; min = 0x800; }
        else if ((c & 0xF8) == 0xF0) { need = 3; cp = c & 0x07; min = 0x10000; }
        else return 0;
        if (i + need >= n) return 0;
        for (size_t k = 1; k <= need; k++) {
            if ((s[i + k] & 0xC0) != 0x80) return 0;
            cp = (cp << 6) | (s[i + k] & 0x3F);
        }
        if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return 0;
        i += need + 1;
    }
    return 1;
}

static qj_value *node_new(parser *ps, qj_type t) {
    if (++ps->nodes > QJ_MAX_NODES) { perr(ps, "JSON document has too many nodes"); return NULL; }
    qj_value *v = calloc(1, sizeof *v);
    if (!v) { perr(ps, "out of memory"); return NULL; }
    v->type = t;
    return v;
}

static int push(parser *ps, qj_value *parent, qj_value *child, char *key) {
    if (parent->count == parent->capacity) {
        size_t cap = parent->capacity ? parent->capacity * 2 : 4;
        qj_value **ni = realloc(parent->items, cap * sizeof *ni);
        if (!ni) return perr(ps, "out of memory");
        parent->items = ni;
        if (parent->type == QJ_OBJECT) {
            char **nk = realloc(parent->keys, cap * sizeof *nk);
            if (!nk) return perr(ps, "out of memory");
            parent->keys = nk;
        }
        parent->capacity = cap;
    }
    parent->items[parent->count] = child;
    if (parent->type == QJ_OBJECT) parent->keys[parent->count] = key;
    parent->count++;
    return 0;
}

static void skip_ws(parser *ps) {
    while (ps->p < ps->end && (*ps->p == ' ' || *ps->p == '\t' || *ps->p == '\n' || *ps->p == '\r')) ps->p++;
}

static int hex4(const unsigned char *p, uint32_t *out) {
    uint32_t v = 0;
    for (int i = 0; i < 4; i++) {
        unsigned c = p[i]; v <<= 4;
        if (c >= '0' && c <= '9') v |= c - '0';
        else if (c >= 'a' && c <= 'f') v |= c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') v |= c - 'A' + 10;
        else return -1;
    }
    *out = v;
    return 0;
}

static void put_utf8(char *dst, size_t *n, uint32_t cp) {
    if (cp < 0x80) dst[(*n)++] = (char)cp;
    else if (cp < 0x800) { dst[(*n)++] = (char)(0xC0 | (cp >> 6)); dst[(*n)++] = (char)(0x80 | (cp & 0x3F)); }
    else if (cp < 0x10000) { dst[(*n)++] = (char)(0xE0 | (cp >> 12)); dst[(*n)++] = (char)(0x80 | ((cp >> 6) & 0x3F)); dst[(*n)++] = (char)(0x80 | (cp & 0x3F)); }
    else { dst[(*n)++] = (char)(0xF0 | (cp >> 18)); dst[(*n)++] = (char)(0x80 | ((cp >> 12) & 0x3F)); dst[(*n)++] = (char)(0x80 | ((cp >> 6) & 0x3F)); dst[(*n)++] = (char)(0x80 | (cp & 0x3F)); }
}

/* Parses a string literal starting at the opening quote. */
static int parse_string(parser *ps, char **out, size_t *out_len) {
    ps->p++; /* opening quote */
    size_t cap = (size_t)(ps->end - ps->p) + 1; /* decoded form is never longer than the source */
    char *buf = malloc(cap);
    if (!buf) return perr(ps, "out of memory");
    size_t n = 0;
    for (;;) {
        if (ps->p >= ps->end) { free(buf); return perr(ps, "unterminated string"); }
        unsigned c = *ps->p++;
        if (c == '"') break;
        if (c < 0x20) { free(buf); return perr(ps, "control character in string"); }
        if (c != '\\') { buf[n++] = (char)c; continue; }
        if (ps->p >= ps->end) { free(buf); return perr(ps, "bad escape"); }
        c = *ps->p++;
        switch (c) {
        case '"': buf[n++] = '"'; break;
        case '\\': buf[n++] = '\\'; break;
        case '/': buf[n++] = '/'; break;
        case 'b': buf[n++] = '\b'; break;
        case 'f': buf[n++] = '\f'; break;
        case 'n': buf[n++] = '\n'; break;
        case 'r': buf[n++] = '\r'; break;
        case 't': buf[n++] = '\t'; break;
        case 'u': {
            uint32_t cp, lo;
            if (ps->end - ps->p < 4 || hex4(ps->p, &cp)) { free(buf); return perr(ps, "bad \\u escape"); }
            ps->p += 4;
            if (cp >= 0xD800 && cp <= 0xDBFF) {
                if (ps->end - ps->p < 6 || ps->p[0] != '\\' || ps->p[1] != 'u' || hex4(ps->p + 2, &lo) || lo < 0xDC00 || lo > 0xDFFF) {
                    free(buf); return perr(ps, "unpaired surrogate");
                }
                ps->p += 6;
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
            } else if (cp >= 0xDC00 && cp <= 0xDFFF) { free(buf); return perr(ps, "unpaired surrogate"); }
            if (cp == 0) { free(buf); return perr(ps, "NUL character in string"); }
            /* A \uXXXX escape (6 bytes) always decodes to <= 4 bytes, so cap is sufficient. */
            put_utf8(buf, &n, cp);
            break;
        }
        default: free(buf); return perr(ps, "bad escape");
        }
    }
    buf[n] = '\0';
    *out = buf; *out_len = n;
    return 0;
}

static int parse_value(parser *ps, int depth, qj_value **out);

static int parse_value(parser *ps, int depth, qj_value **out) {
    if (depth > QJ_MAX_DEPTH) return perr(ps, "JSON nesting too deep");
    skip_ws(ps);
    if (ps->p >= ps->end) return perr(ps, "unexpected end of JSON");
    unsigned c = *ps->p;
    qj_value *v = NULL;
    if (c == '{' || c == '[') {
        v = node_new(ps, c == '{' ? QJ_OBJECT : QJ_ARRAY);
        if (!v) return -1;
        ps->p++;
        skip_ws(ps);
        unsigned close = c == '{' ? '}' : ']';
        if (ps->p < ps->end && *ps->p == close) { ps->p++; *out = v; return 0; }
        for (;;) {
            char *key = NULL; size_t klen = 0;
            if (c == '{') {
                skip_ws(ps);
                if (ps->p >= ps->end || *ps->p != '"') { qj_free(v); return perr(ps, "object key expected"); }
                if (parse_string(ps, &key, &klen)) { qj_free(v); return -1; }
                for (size_t i = 0; i < v->count; i++) {
                    if (strcmp(v->keys[i], key) == 0) { free(key); qj_free(v); return perr(ps, "duplicate object key"); }
                }
                skip_ws(ps);
                if (ps->p >= ps->end || *ps->p != ':') { free(key); qj_free(v); return perr(ps, "':' expected"); }
                ps->p++;
            }
            qj_value *child = NULL;
            if (parse_value(ps, depth + 1, &child)) { free(key); qj_free(v); return -1; }
            if (push(ps, v, child, key)) { free(key); qj_free(child); qj_free(v); return -1; }
            skip_ws(ps);
            if (ps->p < ps->end && *ps->p == ',') { ps->p++; continue; }
            if (ps->p < ps->end && *ps->p == close) { ps->p++; break; }
            qj_free(v);
            return perr(ps, "',' or closing bracket expected");
        }
        *out = v; return 0;
    }
    if (c == '"') {
        v = node_new(ps, QJ_STRING);
        if (!v) return -1;
        if (parse_string(ps, &v->string, &v->string_len)) { qj_free(v); return -1; }
        *out = v; return 0;
    }
    if ((size_t)(ps->end - ps->p) >= 4 && memcmp(ps->p, "true", 4) == 0) {
        v = node_new(ps, QJ_BOOL); if (!v) return -1; v->boolean = 1; ps->p += 4; *out = v; return 0;
    }
    if ((size_t)(ps->end - ps->p) >= 5 && memcmp(ps->p, "false", 5) == 0) {
        v = node_new(ps, QJ_BOOL); if (!v) return -1; ps->p += 5; *out = v; return 0;
    }
    if ((size_t)(ps->end - ps->p) >= 4 && memcmp(ps->p, "null", 4) == 0) {
        v = node_new(ps, QJ_NULL); if (!v) return -1; ps->p += 4; *out = v; return 0;
    }
    if (c == '-' || (c >= '0' && c <= '9')) {
        const unsigned char *s = ps->p;
        int neg = 0;
        if (*s == '-') { neg = 1; s++; }
        if (s >= ps->end || *s < '0' || *s > '9') return perr(ps, "bad number");
        if (*s == '0' && s + 1 < ps->end && s[1] >= '0' && s[1] <= '9') return perr(ps, "leading zero in number");
        uint64_t acc = 0;
        while (s < ps->end && *s >= '0' && *s <= '9') {
            unsigned d = *s - '0';
            if (acc > (UINT64_MAX - d) / 10) return perr(ps, "integer out of range");
            acc = acc * 10 + d; s++;
        }
        if (acc > (neg ? (UINT64_C(1) << 63) : (UINT64_C(1) << 63) - 1)) return perr(ps, "integer out of range");
        if (s < ps->end && (*s == '.' || *s == 'e' || *s == 'E')) return perr(ps, "non-integer numbers are not supported");
        v = node_new(ps, QJ_INT);
        if (!v) return -1;
        v->integer = neg ? (acc == (UINT64_C(1) << 63) ? INT64_MIN : -(int64_t)acc) : (int64_t)acc;
        ps->p = s; *out = v; return 0;
    }
    return perr(ps, "unexpected character in JSON");
}

int qj_parse(const char *text, size_t len, qj_value **out, char *err, size_t err_size) {
    parser ps = { (const unsigned char *)text, (const unsigned char *)text + len, 0, err, err_size };
    *out = NULL;
    if (!valid_utf8((const unsigned char *)text, len)) return perr(&ps, "manifest is not valid UTF-8");
    qj_value *root = NULL;
    if (parse_value(&ps, 0, &root)) return -1;
    skip_ws(&ps);
    if (ps.p != ps.end) { qj_free(root); return perr(&ps, "trailing data after JSON document"); }
    *out = root;
    return 0;
}

void qj_free(qj_value *v) {
    if (!v) return;
    for (size_t i = 0; i < v->count; i++) {
        qj_free(v->items[i]);
        if (v->type == QJ_OBJECT) free(v->keys[i]);
    }
    free(v->items); free(v->keys); free(v->string); free(v);
}

const qj_value *qj_get(const qj_value *object, const char *key) {
    if (!object || object->type != QJ_OBJECT) return NULL;
    for (size_t i = 0; i < object->count; i++) if (strcmp(object->keys[i], key) == 0) return object->items[i];
    return NULL;
}

/* ---- canonical serializer ---- */
typedef struct { char *buf; size_t len, cap; int oom; } sbuf;

static void sput(sbuf *b, const char *s, size_t n) {
    if (b->oom) return;
    if (b->len + n + 1 > b->cap) {
        size_t cap = b->cap ? b->cap : 256;
        while (cap < b->len + n + 1) cap *= 2;
        char *nb = realloc(b->buf, cap);
        if (!nb) { b->oom = 1; return; }
        b->buf = nb; b->cap = cap;
    }
    memcpy(b->buf + b->len, s, n); b->len += n; b->buf[b->len] = '\0';
}

static void sput_string(sbuf *b, const char *s, size_t n) {
    sput(b, "\"", 1);
    size_t start = 0;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        const char *rep = NULL; char tmp[8];
        if (c == '"') rep = "\\\""; else if (c == '\\') rep = "\\\\";
        else if (c == '\n') rep = "\\n"; else if (c == '\r') rep = "\\r"; else if (c == '\t') rep = "\\t";
        else if (c == '\b') rep = "\\b"; else if (c == '\f') rep = "\\f";
        else if (c < 0x20) { snprintf(tmp, sizeof tmp, "\\u%04x", c); rep = tmp; }
        if (rep) { sput(b, s + start, i - start); sput(b, rep, strlen(rep)); start = i + 1; }
    }
    sput(b, s + start, n - start);
    sput(b, "\"", 1);
}

typedef struct { const char *key; const qj_value *value; } kv;

static int kv_cmp(const void *a, const void *b) {
    return strcmp(((const kv *)a)->key, ((const kv *)b)->key);
}

static void emit(sbuf *b, const qj_value *v, const char *omit) {
    char tmp[32];
    switch (v->type) {
    case QJ_NULL: sput(b, "null", 4); break;
    case QJ_BOOL: if (v->boolean) sput(b, "true", 4); else sput(b, "false", 5); break;
    case QJ_INT: snprintf(tmp, sizeof tmp, "%lld", (long long)v->integer); sput(b, tmp, strlen(tmp)); break;
    case QJ_STRING: sput_string(b, v->string, v->string_len); break;
    case QJ_ARRAY:
        sput(b, "[", 1);
        for (size_t i = 0; i < v->count; i++) { if (i) sput(b, ",", 1); emit(b, v->items[i], NULL); }
        sput(b, "]", 1);
        break;
    case QJ_OBJECT: {
        kv *tab = malloc((v->count ? v->count : 1) * sizeof *tab);
        if (!tab) { b->oom = 1; return; }
        size_t n = 0;
        for (size_t i = 0; i < v->count; i++) {
            if (omit && strcmp(v->keys[i], omit) == 0) continue;
            tab[n].key = v->keys[i]; tab[n].value = v->items[i]; n++;
        }
        qsort(tab, n, sizeof *tab, kv_cmp);
        sput(b, "{", 1);
        for (size_t i = 0; i < n; i++) {
            if (i) sput(b, ",", 1);
            sput_string(b, tab[i].key, strlen(tab[i].key));
            sput(b, ":", 1);
            emit(b, tab[i].value, NULL);
        }
        sput(b, "}", 1);
        free(tab);
        break;
    }
    }
}

int qj_canonical(const qj_value *value, const char *omit_top_key, char **out, size_t *out_len) {
    sbuf b = { NULL, 0, 0, 0 };
    sput(&b, "", 0);
    emit(&b, value, omit_top_key);
    if (b.oom) { free(b.buf); return -1; }
    *out = b.buf; *out_len = b.len;
    return 0;
}
