package nzi.fhir.validator.licensing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generated third-party listing (license-maven-plugin, generate-resources) is covered by the committed license
 * texts and clarifications that ship next to it in the jar (FR-013, FR-015, SC-006).
 */
class ThirdPartyNoticesTest {
    private static final Path LISTING = Path.of("target/classes/META-INF/THIRD-PARTY.txt");
    private static final Path TEXTS = Path.of("src/main/resources/META-INF/third-party-licenses");
    private static final Path CLARIFICATIONS = Path.of("src/main/resources/META-INF/THIRD-PARTY-CLARIFICATIONS.md");
    private static final Path OVERRIDES = Path.of("src/license/THIRD-PARTY.properties");

    /** {@code (Apache-2.0) (MIT) Name (group:artifact:version - url)}. */
    private static final Pattern LINE = Pattern.compile("^\\s*((?:\\([^()]+\\)\\s*)+)(.*)\\(([^:\\s]+):([^:\\s]+):([^\\s]+) - .*\\)\\s*$");
    private static final Pattern LICENSE_ID = Pattern.compile("\\(([^()]+)\\)");

    private static List<Entry> entries;
    private static String clarifications;

    record Entry(String line, List<String> licenses, String group, String artifact) {
    }

    @BeforeAll
    static void load() throws IOException {
        assertTrue(Files.isRegularFile(LISTING), LISTING + " missing: the license plugin did not run");
        entries = new ArrayList<>();
        for (String line : Files.readAllLines(LISTING, StandardCharsets.UTF_8)) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            List<String> licenses = new ArrayList<>();
            Matcher id = LICENSE_ID.matcher(m.group(1));
            while (id.find()) {
                licenses.add(id.group(1).trim());
            }
            entries.add(new Entry(line.trim(), licenses, m.group(3), m.group(4)));
        }
        clarifications = Files.readString(CLARIFICATIONS, StandardCharsets.UTF_8);
    }

    @Test
    void listsTheBundledLibraries() {
        assertFalse(entries.isEmpty(), "no artifacts listed in " + LISTING);
    }

    @Test
    void noLibraryHasAnUnknownLicense() {
        List<String> unknown = entries.stream().filter(e -> e.line().contains("Unknown license")).map(Entry::line).toList();
        assertTrue(unknown.isEmpty(), "add these to " + OVERRIDES + ": " + unknown);
    }

    @Test
    void everyLibraryHasAShippedLicenseText() {
        List<String> uncovered = entries.stream()
                .filter(e -> e.licenses().stream().noneMatch(id -> Files.isRegularFile(TEXTS.resolve(id + ".txt"))))
                .map(Entry::line)
                .toList();
        assertTrue(uncovered.isEmpty(), "no license text in " + TEXTS + " for: " + uncovered);
    }

    @Test
    void everyMultiLicensedLibraryIsClarified() {
        List<String> unclarified = entries.stream()
                .filter(e -> e.licenses().size() > 1)
                .filter(e -> !clarifications.contains(e.group() + ":" + e.artifact())
                        && !clarifications.contains(e.group() + ":*"))
                .map(Entry::line)
                .toList();
        assertTrue(unclarified.isEmpty(), "explain the elected license in " + CLARIFICATIONS + " for: " + unclarified);
    }

    @Test
    void everyLicenseOverrideIsClarified() throws IOException {
        Properties overrides = new Properties();
        try (Reader in = Files.newBufferedReader(OVERRIDES, StandardCharsets.UTF_8)) {
            overrides.load(in);
        }
        assertFalse(overrides.isEmpty(), OVERRIDES + " has no entries");
        for (String key : overrides.stringPropertyNames()) {
            String coordinates = key.replace("--", ":");
            assertTrue(clarifications.contains(coordinates), coordinates + " is overridden but not explained in " + CLARIFICATIONS);
        }
    }
}
