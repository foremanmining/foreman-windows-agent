package mn.foreman.windowsagent;

import mn.foreman.windowsagent.foreman.AppManifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link FileUtils}. */
class FileUtilsTest {

    /** Verifies that {@link FileUtils#forFileIn} visits matching files. */
    @Test
    void forFileInVisitsMatchingChildren(@TempDir final Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("dirA"));
        Files.createDirectory(tempDir.resolve("dirB"));
        Files.createFile(tempDir.resolve("file.txt"));

        final Set<String> visited = new HashSet<>();
        FileUtils.forFileIn(
                tempDir.toString(),
                File::isDirectory,
                file -> visited.add(file.getName()));

        assertEquals(
                new HashSet<>(java.util.Arrays.asList("dirA", "dirB")),
                visited);
    }

    /**
     * Verifies that {@link FileUtils#forFileIn} silently does nothing when
     * the path does not exist.
     */
    @Test
    void forFileInIgnoresMissingPath(@TempDir final Path tempDir) {
        final String missing = tempDir.resolve("does-not-exist").toString();

        final Set<String> visited = new HashSet<>();
        FileUtils.forFileIn(
                missing,
                file -> true,
                file -> visited.add(file.getName()));

        assertTrue(visited.isEmpty());
    }

    /**
     * Verifies that the filter excludes files from being visited.
     */
    @Test
    void forFileInRespectsFilter(@TempDir final Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("dir"));
        Files.createFile(tempDir.resolve("a.log"));
        Files.createFile(tempDir.resolve("b.log"));

        final Set<String> visited = new HashSet<>();
        FileUtils.forFileIn(
                tempDir.toString(),
                file -> file.getName().endsWith(".log"),
                file -> visited.add(file.getName()));

        assertEquals(
                new HashSet<>(java.util.Arrays.asList("a.log", "b.log")),
                visited);
    }

    /**
     * Verifies that {@link FileUtils#toFilePath} concatenates the agent home,
     * alias, version, folder and target as expected.
     */
    @Test
    void toFilePathBuildsExpectedPath() {
        final AppManifest manifest = new AppManifest();
        manifest.alias = "foreman-pickaxe";

        final Path path =
                FileUtils.toFilePath(
                        "C:\\agent",
                        manifest,
                        "1.2.3",
                        AppFolder.CONF,
                        "pickaxe.yml");

        final Path expected =
                Paths.get(
                        "C:\\agent"
                                + File.separator
                                + "foreman-pickaxe-1.2.3"
                                + File.separator
                                + "conf"
                                + File.separator
                                + "pickaxe.yml");
        assertEquals(expected, path);
    }

    /**
     * Verifies that the bin folder produces the correct sub-path.
     */
    @Test
    void toFilePathHandlesBinFolder() {
        final AppManifest manifest = new AppManifest();
        manifest.alias = "windows-agent";

        final Path path =
                FileUtils.toFilePath(
                        "/opt/agent",
                        manifest,
                        "9.9.9",
                        AppFolder.BIN,
                        "service-start.bat");

        assertTrue(path.toString().contains("windows-agent-9.9.9"));
        assertTrue(path.toString().contains("bin"));
        assertTrue(path.toString().endsWith("service-start.bat"));
    }
}
