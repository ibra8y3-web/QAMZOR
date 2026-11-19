# QAMZOR Native SDK - plain Makefile (POSIX). Requires OpenSSL >= 3.0 (libcrypto) and zlib.
# Override OPENSSL_CFLAGS / OPENSSL_LIBS when OpenSSL is not in the default search path.
CC ?= cc
CFLAGS ?= -O2
OPENSSL_CFLAGS ?= $(shell pkg-config --cflags libcrypto 2>/dev/null)
OPENSSL_LIBS ?= $(shell pkg-config --libs libcrypto 2>/dev/null || echo -lcrypto)
WARN = -std=gnu11 -Wall -Wextra -Wpedantic -Werror
INC = -Iinclude -Isrc
SRC = src/qzra.c src/qzra_json.c

all: libqzra.a build/compat_runner

build:
	mkdir -p build

build/%.o: src/%.c | build
	$(CC) $(CFLAGS) $(WARN) $(INC) $(OPENSSL_CFLAGS) -fPIC -c $< -o $@

libqzra.a: build/qzra.o build/qzra_json.o
	$(AR) rcs $@ $^

build/compat_runner: tests/compat_runner.c libqzra.a | build
	$(CC) $(CFLAGS) $(WARN) $(INC) $(OPENSSL_CFLAGS) $< libqzra.a $(OPENSSL_LIBS) -lz -o $@

build/cpp_smoke: tests/cpp_smoke.cpp libqzra.a | build
	$(CXX) $(CFLAGS) -std=c++17 -Wall -Wextra -Wpedantic -Werror $(INC) $(OPENSSL_CFLAGS) $< libqzra.a $(OPENSSL_LIBS) -lz -o $@

build/mutation_fuzz: tests/mutation_fuzz.c src/qzra.c src/qzra_json.c | build
	$(CC) -fsanitize=address,undefined -fno-sanitize-recover=undefined -g -O1 $(WARN) $(INC) $(OPENSSL_CFLAGS) $^ $(OPENSSL_LIBS) -lz -o $@

check: build/compat_runner build/cpp_smoke
	build/compat_runner ../compat
	build/cpp_smoke ../compat

fuzz: build/mutation_fuzz
	build/mutation_fuzz ../compat 300

clean:
	rm -rf build libqzra.a
.PHONY: all check fuzz clean
