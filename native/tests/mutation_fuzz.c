/* Deterministic mutation smoke-fuzzer: corrupts vector bytes and requires libqzra to fail
 * cleanly (never crash). Build with -fsanitize=address,undefined for meaningful results.
 * Usage: mutation_fuzz <compat-dir> [iterations-per-vector] */
#define _POSIX_C_SOURCE 200809L
#include "qzra.h"
#include "../src/qzra_json.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static uint64_t rng = 0x9E3779B97F4A7C15ull;
static uint64_t next(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return rng; }

static char *slurp(const char *p, size_t *n) {
    FILE *f = fopen(p, "rb"); if (!f) return NULL;
    fseek(f, 0, SEEK_END); long l = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc((size_t)l + 1);
    if (!b || fread(b, 1, (size_t)l, f) != (size_t)l) { fclose(f); free(b); return NULL; }
    fclose(f); *n = (size_t)l; return b;
}

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    int iters = argc > 2 ? atoi(argv[2]) : 200;
    char mp[4096]; snprintf(mp, sizeof mp, "%s/manifest.json", argv[1]);
    size_t ml; char *mt = slurp(mp, &ml);
    qj_value *root = NULL; char pe[128];
    if (!mt || qj_parse(mt, ml, &root, pe, sizeof pe)) return 2;
    const qj_value *vecs = qj_get(root, "vectors");
    char tmp[] = "/tmp/qzra-fuzz-XXXXXX";
    int fd = mkstemp(tmp); if (fd < 0) return 2; close(fd);
    long runs = 0, opened = 0;
    for (size_t i = 0; i < vecs->count; i++) {
        const qj_value *v = vecs->items[i];
        char vp[4096]; snprintf(vp, sizeof vp, "%s/vectors/%s", argv[1], qj_get(v, "file")->string);
        size_t n; char *orig = slurp(vp, &n); if (!orig) return 2;
        const qj_value *pw = qj_get(v, "password"), *fps = qj_get(v, "folder_passwords");
        /* Encrypted archives spend ~100 ms per scrypt; mutate fewer of them. */
        int budget = pw->type == QJ_STRING ? iters / 20 + 1 : iters;
        for (int it = 0; it < budget; it++) {
            char *m = malloc(n); memcpy(m, orig, n);
            size_t len = n;
            int flips = 1 + (int)(next() % 4);
            /* Concentrate on the header/manifest region most of the time. */
            for (int f = 0; f < flips; f++) {
                size_t region = (next() % 4 == 0) ? n : (n < 600 ? n : 600);
                m[next() % region] ^= (char)(1u << (next() % 8));
            }
            if (next() % 8 == 0) len = (size_t)(next() % (n + 1));
            FILE *o = fopen(tmp, "wb"); fwrite(m, 1, len, o); fclose(o); free(m);
            qzra_archive *a = NULL; qzra_error err;
            qzra_status st = qzra_open(tmp, pw->type == QJ_STRING ? pw->string : NULL, &a, &err);
            for (size_t k = 0; st == QZRA_OK && k < fps->count; k++) st = qzra_unlock_folder(a, fps->keys[k], fps->items[k]->string, &err);
            if (st == QZRA_OK) { opened++; qzra_verify(a, NULL, &err); }
            qzra_close(a);
            runs++;
        }
        free(orig);
    }
    unlink(tmp);
    printf("mutation fuzz: %ld runs, %ld still opened, no crashes\n", runs, opened);
    qj_free(root); free(mt);
    return 0;
}
