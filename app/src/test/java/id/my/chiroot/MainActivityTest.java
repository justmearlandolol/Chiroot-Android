package id.my.chiroot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;

/** Pure JVM regression tests; these do not claim Android device integration coverage. */
public class MainActivityTest {
    @Test public void colorsRejectMarkupAndInvalidValues() {
        assertEquals("#D7ED86", MainActivity.safeColor("#d7ed86", "#000000"));
        assertEquals("#000000", MainActivity.safeColor("url(javascript:alert(1))", "#000000"));
        assertEquals("#000000", MainActivity.safeColor(null, "#000000"));
    }

    @Test public void svgEscapesAllXmlAttributeDelimiters() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", MainActivity.escapeXml("&<>\"'"));
    }

    @Test public void playbackClockHandlesMinutesAndUnknownDuration() {
        assertEquals("0:00", MainActivity.humanTime(0));
        assertEquals("0:00", MainActivity.humanTime(-1));
        assertEquals("1:05", MainActivity.humanTime(65000));
        assertEquals("61:01", MainActivity.humanTime(3661000));
    }

    @Test public void configurationTrimsTrailingSlashes() {
        assertEquals("https://example.invalid", MainActivity.trimSlash(" https://example.invalid/// "));
        assertEquals("", MainActivity.trimSlash(null));
    }

    @Test public void labelsAreBounded() {
        assertEquals("abc", MainActivity.trim("abc", 3));
        assertEquals("ab…", MainActivity.trim("abcd", 3));
    }

    @Test public void audioHashMatchesKnownSha256Vector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                MainActivity.sha256Bytes("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void backupIsARealZipWithJsonEntry() throws Exception {
        byte[] zip = MainActivity.zipBytes("{\"app\":\"Chiroot Android\"}");
        assertTrue(zip.length > 4);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            assertEquals("chiroot-backup.json", in.getNextEntry().getName());
            assertArrayEquals("{\"app\":\"Chiroot Android\"}".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
    }
}
