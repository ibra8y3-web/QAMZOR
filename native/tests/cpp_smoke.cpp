// C++ wrapper smoke test: uses the public Compatibility Suite vectors; no secrets beyond the published test passwords.
#include "qzra.hpp"
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>

#define REQUIRE(cond) do { if (!(cond)) { std::fprintf(stderr, "FAILED %s:%d: %s\n", __FILE__, __LINE__, #cond); return 1; } } while (0)

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    namespace fs = std::filesystem;
    const fs::path vectors = fs::path(argv[1]) / "vectors";
    const std::string master = "QAMZOR-compat-master";

    auto a = qzra::Archive::open((vectors / "positive-v1.3-folder-scopes.qzra").string(), master);
    REQUIRE(a.versionMinor() == 3);
    REQUIRE(!a.entries().empty());

    bool locked = false;
    try { a.verify(); } catch (const qzra::Error &e) { locked = e.status() == QZRA_ERR_FOLDER_LOCKED; }
    REQUIRE(locked);

    a.unlockFolder("Project/Vault", "QAMZOR-compat-vault");
    a.unlockFolder("Project/Vault/Inner", "QAMZOR-compat-inner");
    const auto r = a.verify();
    REQUIRE(r.files_checked == 7 && r.version_minor == 3);

    const fs::path out = fs::temp_directory_path() / "qzra-cpp-smoke";
    fs::remove_all(out);
    a.extract(out.string());
    std::ifstream f(out / "Project" / "Vault" / "Inner" / "deep.txt");
    std::string line; std::getline(f, line);
    REQUIRE(line == "inner secret");
    fs::remove_all(out);

    bool wrong = false;
    try { qzra::Archive::open((vectors / "positive-v1.2-encrypted.qzra").string(), "wrong"); }
    catch (const qzra::Error &e) { wrong = std::string(e.category()) == "AUTHENTICATION_FAILED"; }
    REQUIRE(wrong);

    bool moved = false;
    qzra::Archive b = qzra::Archive::open((vectors / "positive-v1.1-plain.qzra").string());
    qzra::Archive c = std::move(b);
    moved = c.entries().size() > 0;
    REQUIRE(moved);
    std::cout << "cpp smoke OK\n";
    return 0;
}
