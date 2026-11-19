/* Internal strict JSON reader used by the QAMZOR Native SDK (not a public API). */
#ifndef QZRA_JSON_H
#define QZRA_JSON_H
#include <stddef.h>
#include <stdint.h>

typedef enum { QJ_NULL, QJ_BOOL, QJ_INT, QJ_STRING, QJ_ARRAY, QJ_OBJECT } qj_type;

typedef struct qj_value qj_value;
struct qj_value {
    qj_type type;
    int boolean;
    int64_t integer;
    char *string;          /* NUL-terminated UTF-8; never contains an embedded NUL */
    size_t string_len;
    qj_value **items;      /* array elements or object values */
    char **keys;           /* object keys (same length as items) */
    size_t count;
    size_t capacity;
};

#define QJ_MAX_DEPTH 64
#define QJ_MAX_NODES 4000000u

/* Parses exactly one JSON document. Accepts only integers (no fractions/exponents),
 * valid UTF-8, no duplicate object keys, no embedded NUL. Returns 0 on success. */
int qj_parse(const char *text, size_t len, qj_value **out, char *err, size_t err_size);
void qj_free(qj_value *value);
const qj_value *qj_get(const qj_value *object, const char *key);
/* Serializes like Python json.dumps(sort_keys=True, ensure_ascii=False, separators=(",",":")),
 * omitting `omit_top_key` from the top-level object (may be NULL). Caller frees *out. */
int qj_canonical(const qj_value *value, const char *omit_top_key, char **out, size_t *out_len);
#endif
