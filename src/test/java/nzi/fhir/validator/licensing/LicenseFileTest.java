package nzi.fhir.validator.licensing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LICENSE.md is the Required Notice, a blank line, then the official PolyForm Noncommercial 1.0.0 text
 * (polyformproject/polyform-licenses, tag 1.0.0), byte for byte.
 */
class LicenseFileTest {
    static final String REQUIRED_NOTICE =
            "Required Notice: Copyright 2025-2026 Md Nazrul Islam (https://github.com/nazrulworld)";
    private static final String OFFICIAL_TEXT_SHA256 =
            "c0ea4a896d2c8c394b29f9427589996db826cd501c512279ff0ed3ef48fabbe5";
    private static final Path LICENSE = Path.of("LICENSE.md");

    @Test
    void startsWithTheRequiredNoticeAndABlankLine() throws IOException {
        String text = Files.readString(LICENSE, StandardCharsets.UTF_8);
        assertTrue(text.startsWith(REQUIRED_NOTICE + "\n\n"), "LICENSE.md must open with the Required Notice line");
    }

    @Test
    void bodyIsTheUnalteredOfficialText() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(LICENSE);
        int bodyStart = (REQUIRED_NOTICE + "\n\n").getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes.length > bodyStart, "LICENSE.md has no license text");
        byte[] body = Arrays.copyOfRange(bytes, bodyStart, bytes.length);

        assertTrue(new String(body, StandardCharsets.UTF_8).startsWith("# PolyForm Noncommercial License 1.0.0"));
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        assertEquals(OFFICIAL_TEXT_SHA256, sha256, "the license text differs from the official PolyForm text");
    }
}
