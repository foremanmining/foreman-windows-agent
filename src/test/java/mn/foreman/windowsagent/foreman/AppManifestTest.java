package mn.foreman.windowsagent.foreman;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link AppManifest}. */
class AppManifestTest {

    /** The mapper used by these tests. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Verifies that a fully-populated manifest can be deserialized. */
    @Test
    void deserializesFullManifest() throws Exception {
        final String json =
                "{" +
                        "\"alias\":\"foreman-pickaxe\"," +
                        "\"app\":\"Foreman Pickaxe\"," +
                        "\"executable\":\"pickaxe.bat\"," +
                        "\"version\":\"1.2.3\"," +
                        "\"windows\":true," +
                        "\"github\":{" +
                        "  \"name\":\"foreman-pickaxe-1.2.3.zip\"," +
                        "  \"zipUrl\":\"https://example.com/pickaxe.zip\"" +
                        "}," +
                        "\"conf\":{" +
                        "  \"file\":\"pickaxe.yml\"," +
                        "  \"apiKeyPattern\":\"REPLACE_API\"," +
                        "  \"clientIdPattern\":\"REPLACE_ID\"" +
                        "}" +
                        "}";

        final AppManifest manifest =
                this.mapper.readValue(json, AppManifest.class);

        assertEquals("foreman-pickaxe", manifest.alias);
        assertEquals("Foreman Pickaxe", manifest.app);
        assertEquals("pickaxe.bat", manifest.executable);
        assertEquals("1.2.3", manifest.version);
        assertTrue(manifest.windows);

        assertNotNull(manifest.github);
        assertEquals("foreman-pickaxe-1.2.3.zip", manifest.github.name);
        assertEquals("https://example.com/pickaxe.zip", manifest.github.zipUrl);

        assertNotNull(manifest.conf);
        assertEquals("pickaxe.yml", manifest.conf.file);
        assertEquals("REPLACE_API", manifest.conf.apiKeyPattern);
        assertEquals("REPLACE_ID", manifest.conf.clientIdPattern);
    }

    /** Verifies that unknown JSON properties do not break deserialization. */
    @Test
    void deserializeIgnoresUnknownProperties() throws Exception {
        final String json =
                "{\"alias\":\"a\",\"app\":\"b\",\"version\":\"1\",\"windows\":false,\"unknown\":42}";

        final AppManifest manifest =
                this.mapper.readValue(json, AppManifest.class);

        assertEquals("a", manifest.alias);
        assertEquals("b", manifest.app);
        assertEquals("1", manifest.version);
    }

    /** Verifies that a manifest array can be deserialized. */
    @Test
    void deserializesArray() throws Exception {
        final String json =
                "[" +
                        "{\"alias\":\"a\",\"windows\":true}," +
                        "{\"alias\":\"b\",\"windows\":false}" +
                        "]";

        final AppManifest[] manifests =
                this.mapper.readValue(json, AppManifest[].class);

        assertEquals(2, manifests.length);
        assertEquals("a", manifests[0].alias);
        assertTrue(manifests[0].windows);
        assertEquals("b", manifests[1].alias);
        assertNull(manifests[1].github);
        assertNull(manifests[1].conf);
    }
}
