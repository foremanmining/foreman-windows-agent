package mn.foreman.windowsagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link VersionFactoryImpl}. */
class VersionFactoryImplTest {

    /**
     * Verifies that an empty/missing dist returns an empty map without
     * throwing.
     */
    @Test
    void noDistReturnsEmptyMap(@TempDir final Path tempDir) {
        final VersionFactory factory =
                new VersionFactoryImpl(
                        tempDir.resolve("missing").toString());

        final Map<String, Map<String, String>> versions = factory.getVersions();
        assertNotNull(versions);
        assertTrue(versions.isEmpty());
    }

    /**
     * Verifies that an empty existing dist directory returns an empty map.
     */
    @Test
    void emptyDistReturnsEmptyMap(@TempDir final Path tempDir) {
        final VersionFactory factory =
                new VersionFactoryImpl(tempDir.toString());

        assertTrue(factory.getVersions().isEmpty());
    }

    /**
     * Verifies that foreman/windows-agent applications under numeric scale
     * folders are correctly parsed into versions.
     */
    @Test
    void parsesValidScaleFolders(@TempDir final Path tempDir) throws Exception {
        final Path scaleZero = tempDir.resolve("0");
        final Path scaleOne = tempDir.resolve("1");
        Files.createDirectory(scaleZero);
        Files.createDirectory(scaleOne);

        Files.createDirectory(scaleZero.resolve("foreman-pickaxe-1.0.0"));
        Files.createDirectory(scaleZero.resolve("foreman-autominer-2.0.0"));
        Files.createDirectory(scaleZero.resolve("not-a-foreman-app-3.0.0"));

        Files.createDirectory(scaleOne.resolve("windows-agent-9.9.9"));
        Files.createDirectory(scaleOne.resolve("foreman-pickaxe-1.5.0"));

        final VersionFactory factory =
                new VersionFactoryImpl(tempDir.toString());

        final Map<String, Map<String, String>> versions = factory.getVersions();

        assertEquals(2, versions.size());
        assertEquals("1.0.0", versions.get("0").get("foreman-pickaxe"));
        assertEquals("2.0.0", versions.get("0").get("foreman-autominer"));
        assertNull(versions.get("0").get("not-a-foreman-app"));

        assertEquals("9.9.9", versions.get("1").get("windows-agent"));
        assertEquals("1.5.0", versions.get("1").get("foreman-pickaxe"));
    }

    /**
     * Verifies that non-numeric folders are skipped.
     */
    @Test
    void ignoresNonNumericTopLevel(@TempDir final Path tempDir) throws Exception {
        final Path nonNumeric = tempDir.resolve("hello");
        Files.createDirectory(nonNumeric);
        Files.createDirectory(nonNumeric.resolve("foreman-pickaxe-1.0.0"));

        final VersionFactory factory =
                new VersionFactoryImpl(tempDir.toString());

        assertTrue(factory.getVersions().isEmpty());
    }

    /**
     * Verifies that folder names not matching the {@code alias-version} pattern
     * (i.e. not exactly three dash-separated segments) are skipped.
     */
    @Test
    void ignoresFoldersWithUnexpectedSegments(@TempDir final Path tempDir) throws Exception {
        final Path scale = tempDir.resolve("0");
        Files.createDirectory(scale);

        Files.createDirectory(scale.resolve("foreman"));
        Files.createDirectory(scale.resolve("foreman-pickaxe-extra-1.0.0"));

        final VersionFactory factory =
                new VersionFactoryImpl(tempDir.toString());

        assertTrue(
                factory.getVersions().isEmpty()
                        || !factory.getVersions().containsKey("0")
                        || factory.getVersions().get("0").isEmpty());
    }

    /**
     * Verifies that loose files (not directories) are ignored.
     */
    @Test
    void ignoresFiles(@TempDir final Path tempDir) throws Exception {
        Files.createFile(tempDir.resolve("0"));

        final VersionFactory factory =
                new VersionFactoryImpl(tempDir.toString());

        assertTrue(factory.getVersions().isEmpty());
    }
}
