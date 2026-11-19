package org.qamzor.javasdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Runs the language-neutral QAMZOR Compatibility Suite (compat/manifest.json) against this SDK. */
class CompatibilitySuiteTest {
    private static final Path SUITE = Path.of(System.getProperty("qamzor.compat.dir", "../compat")).toAbsolutePath().normalize();

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test void sdkVersionMatchesBuildVersion() {
        String expected = System.getProperty("qamzor.version");
        if (expected != null) {
            assertEquals(expected, Qamzor.SDK_VERSION);
        }
    }

    @TestFactory Stream<DynamicTest> vectors() throws IOException {
        JsonObject manifest = JsonParser.parseString(Files.readString(SUITE.resolve("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("vectors")) {
            JsonObject vector = element.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(vector.get("id").getAsString(), () -> runVector(vector)));
        }
        assertTrue(!tests.isEmpty());
        return tests.stream();
    }

    private static void runVector(JsonObject vector) throws Exception {
        Path file = SUITE.resolve("vectors").resolve(vector.get("file").getAsString());
        assertEquals(vector.get("sha256").getAsString(), sha256(Files.readAllBytes(file)), "vector file digest");
        JsonObject expect = vector.getAsJsonObject("expect");
        boolean expectOk = "ok".equals(expect.get("outcome").getAsString());
        Path sandbox = Files.createTempDirectory("qamzor-compat-java");
        Path dest = Files.createDirectory(sandbox.resolve("out"));
        try {
            ArchiveReader reader = Qamzor.open(file.toFile());
            JsonElement password = vector.get("password");
            if (password != null && !password.isJsonNull()) {
                reader.password(password.getAsString());
            }
            for (Map.Entry<String, JsonElement> e : vector.getAsJsonObject("folder_passwords").entrySet()) {
                reader.folderPassword(e.getKey(), e.getValue().getAsString());
            }
            QamzorException failure = null;
            List<EntryInfo> entries = null;
            try {
                entries = reader.listEntries();
                reader.verify();
                reader.extractAll(dest.toFile(), false);
            } catch (QamzorException e) {
                failure = e;
            }
            // Nothing may ever appear outside the destination directory.
            try (Stream<Path> top = Files.list(sandbox)) {
                assertEquals(List.of("out"), top.map(p -> p.getFileName().toString()).collect(Collectors.toList()), "files escaped destination");
            }
            if (!expectOk) {
                assertTrue(failure != null, "archive was accepted but " + expect.get("category").getAsString() + " was expected");
                assertEquals(expect.get("category").getAsString(), failure.category().name(), failure.getMessage());
                return;
            }
            assertNull(failure, failure == null ? "" : failure.getMessage());
            JsonArray want = expect.getAsJsonArray("entries");
            List<EntryInfo> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(EntryInfo::path));
            assertEquals(want.size(), sorted.size(), "entry count");
            for (int i = 0; i < want.size(); i++) {
                JsonObject w = want.get(i).getAsJsonObject();
                EntryInfo got = sorted.get(i);
                assertEquals(w.get("path").getAsString(), got.path());
                assertEquals(w.get("type").getAsString(), got.type());
                assertEquals(w.get("size").getAsLong(), got.size());
                Path target = dest.resolve(got.path());
                if (got.isDirectory()) {
                    assertTrue(Files.isDirectory(target), "missing directory " + got.path());
                } else {
                    assertEquals(w.get("sha256").getAsString(), sha256(Files.readAllBytes(target)), "content of " + got.path());
                }
            }
        } finally {
            try (Stream<Path> walk = Files.walk(sandbox)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }
}
