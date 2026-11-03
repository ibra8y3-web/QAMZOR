/* Optional 7z benchmark adapter. It is compiled only when libarchive headers
 * and a C compiler are available; it is not part of QAMZOR's runtime. */
#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static int create_7z(const char *output, int count, char **paths) {
    struct archive *writer = archive_write_new();
    if (!writer) return 2;
    if (archive_write_set_format_7zip(writer) != ARCHIVE_OK ||
        archive_write_open_filename(writer, output) != ARCHIVE_OK) {
        fprintf(stderr, "libarchive create: %s\n", archive_error_string(writer));
        archive_write_free(writer);
        return 2;
    }
    char buffer[1024 * 1024];
    int result = 0;
    for (int i = 0; i < count; ++i) {
        const char *path = paths[i];
        const char *base = strrchr(path, '/');
        base = base ? base + 1 : path;
        struct stat st;
        FILE *input = fopen(path, "rb");
        if (!input || stat(path, &st) != 0) {
            fprintf(stderr, "cannot open input %s: %s\n", path, strerror(errno));
            if (input) fclose(input);
            result = 2;
            break;
        }
        struct archive_entry *entry = archive_entry_new();
        archive_entry_set_pathname(entry, base);
        archive_entry_set_filetype(entry, AE_IFREG);
        archive_entry_set_perm(entry, 0644);
        archive_entry_set_size(entry, st.st_size);
        if (archive_write_header(writer, entry) != ARCHIVE_OK) {
            fprintf(stderr, "libarchive header: %s\n", archive_error_string(writer));
            result = 2;
        } else {
            size_t n;
            while ((n = fread(buffer, 1, sizeof(buffer), input)) > 0) {
                if (archive_write_data(writer, buffer, n) < 0) {
                    fprintf(stderr, "libarchive data: %s\n", archive_error_string(writer));
                    result = 2;
                    break;
                }
            }
            if (ferror(input)) result = 2;
        }
        fclose(input);
        archive_entry_free(entry);
        if (result) break;
    }
    if (archive_write_close(writer) != ARCHIVE_OK) result = 2;
    archive_write_free(writer);
    return result;
}

static int test_7z(const char *path) {
    struct archive *reader = archive_read_new();
    archive_read_support_format_7zip(reader);
    archive_read_support_filter_all(reader);
    if (archive_read_open_filename(reader, path, 10240) != ARCHIVE_OK) {
        fprintf(stderr, "libarchive open: %s\n", archive_error_string(reader));
        archive_read_free(reader);
        return 2;
    }
    struct archive_entry *entry;
    int count = 0;
    int status;
    while ((status = archive_read_next_header(reader, &entry)) == ARCHIVE_OK) {
        char buffer[1024 * 1024];
        la_ssize_t n;
        while ((n = archive_read_data(reader, buffer, sizeof(buffer))) > 0) { }
        if (n < 0) {
            fprintf(stderr, "libarchive read data: %s\n", archive_error_string(reader));
            status = ARCHIVE_FATAL;
            break;
        }
        ++count;
    }
    if (status != ARCHIVE_EOF) {
        fprintf(stderr, "libarchive test: %s\n", archive_error_string(reader));
        archive_read_free(reader);
        return 2;
    }
    archive_read_close(reader);
    archive_read_free(reader);
    printf("OK files=%d\n", count);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 3 && strcmp(argv[1], "create") == 0) {
        return create_7z(argv[2], argc - 3, argv + 3);
    }
    if (argc == 3 && strcmp(argv[1], "test") == 0) {
        return test_7z(argv[2]);
    }
    fprintf(stderr, "usage: %s create output.7z files... | test archive.7z\n", argv[0]);
    return 2;
}
