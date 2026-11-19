// QAMZOR Native SDK - C++17 header-only RAII wrapper over qzra.h.
#ifndef QZRA_HPP
#define QZRA_HPP

#include "qzra.h"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace qzra {

/** Thrown for every failed operation; status() is the stable C status code. */
class Error : public std::runtime_error {
public:
    Error(qzra_status status, const std::string &message) : std::runtime_error(message), status_(status) {}
    qzra_status status() const noexcept { return status_; }
    /** Compatibility Suite category name, e.g. "AUTHENTICATION_FAILED". */
    const char *category() const noexcept { return qzra_status_name(status_); }
private:
    qzra_status status_;
};

struct Entry {
    std::string path;
    bool is_directory;
    std::uint64_t size;
};

struct VerifyResult {
    std::uint64_t files_checked;
    std::uint64_t bytes_checked;
    int version_minor;
};

class Archive {
public:
    /** Opens an archive; pass std::nullopt as password for QZRA 1.0/1.1. */
    static Archive open(const std::string &path, const std::optional<std::string> &password = std::nullopt) {
        qzra_archive *raw = nullptr;
        qzra_error err{};
        const qzra_status st = qzra_open(path.c_str(), password ? password->c_str() : nullptr, &raw, &err);
        check(st, err);
        return Archive(raw);
    }

    Archive(const Archive &) = delete;
    Archive &operator=(const Archive &) = delete;
    Archive(Archive &&other) noexcept : handle_(std::exchange(other.handle_, nullptr)) {}
    Archive &operator=(Archive &&other) noexcept {
        if (this != &other) { qzra_close(handle_); handle_ = std::exchange(other.handle_, nullptr); }
        return *this;
    }
    ~Archive() { qzra_close(handle_); }

    int versionMinor() const noexcept { return qzra_version_minor(handle_); }

    std::vector<Entry> entries() const {
        std::vector<Entry> out;
        const std::size_t n = qzra_entry_count(handle_);
        out.reserve(n);
        for (std::size_t i = 0; i < n; ++i) {
            qzra_entry e{};
            if (qzra_entry_at(handle_, i, &e) == QZRA_OK) out.push_back({e.path, e.is_directory != 0, e.size});
        }
        return out;
    }

    void unlockFolder(const std::string &folderPath, const std::string &password) {
        qzra_error err{};
        check(qzra_unlock_folder(handle_, folderPath.c_str(), password.c_str(), &err), err);
    }

    VerifyResult verify() {
        qzra_verify_result r{};
        qzra_error err{};
        check(qzra_verify(handle_, &r, &err), err);
        return {r.files_checked, r.bytes_checked, r.version_minor};
    }

    void extract(const std::string &destination, bool overwrite = false) {
        qzra_error err{};
        check(qzra_extract(handle_, destination.c_str(), overwrite ? 1 : 0, &err), err);
    }

private:
    explicit Archive(qzra_archive *h) noexcept : handle_(h) {}
    static void check(qzra_status st, const qzra_error &err) {
        if (st != QZRA_OK) throw Error(st, err.message[0] ? err.message : qzra_status_name(st));
    }
    qzra_archive *handle_;
};

} // namespace qzra
#endif
