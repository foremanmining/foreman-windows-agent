package mn.foreman.windowsagent;

import mn.foreman.windowsagent.foreman.AppManifest;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Utilities for operating on files. */
public class FileUtils {

    /**
     * Finds all of the files in the provided path and provides them to the
     * provided {@link Consumer} to perform an action.
     *
     * @param path     The path.
     * @param filter   The filter.
     * @param consumer The consumer.
     */
    public static void forFileIn(
            final String path,
            final Predicate<File> filter,
            final Consumer<File> consumer) {
        final File agentRoot = new File(path);
        final File[] agentFiles = agentRoot.listFiles();
        if (agentFiles != null) {
            Arrays
                    .stream(agentFiles)
                    .filter(filter)
                    .forEach(consumer);
        }
    }

    /**
     * Creates a path to the file based on the provided home.
     *
     * @param agentHome The agent home.
     * @param manifest  The manifest
     * @param version   The version.
     * @param folder    The folder.
     * @param target    The target.
     *
     * @return The path.
     */
    public static Path toFilePath(
            final String agentHome,
            final AppManifest manifest,
            final String version,
            final AppFolder folder,
            final String target) {
        return Paths.get(
                agentHome +
                        File.separator +
                        manifest.alias +
                        "-" +
                        version +
                        File.separator +
                        folder.getFolder() +
                        File.separator +
                        target);
    }
}