package mn.foreman.windowsagent;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link VersionFactory} implementation that will obtain the currently
 * installed versions for a dist.
 */
public class VersionFactoryImpl
        implements VersionFactory {

    /** The logger for this class. */
    private static final Logger LOG =
            LoggerFactory.getLogger(VersionFactoryImpl.class);

    /** The dist. */
    private final String agentDist;

    /**
     * Constructor.
     *
     * @param agentDist The dist.
     */
    public VersionFactoryImpl(final String agentDist) {
        this.agentDist = agentDist;
    }

    @Override
    public Map<String, Map<String, String>> getVersions() {
        final Map<String, Map<String, String>> versions =
                new ConcurrentHashMap<>();

        FileUtils.forFileIn(
                this.agentDist,
                File::isDirectory,
                distFile -> {
                    final String distName = distFile.getName();
                    if (StringUtils.isNumeric(distName)) {
                        // Must be a dist scale folder
                        FileUtils.forFileIn(
                                this.agentDist + File.separator + distName,
                                File::isDirectory,
                                file -> {
                                    final String fileName = file.getName();
                                    if (isForeman(fileName)) {
                                        final String[] regions = fileName.split("-");
                                        if (regions.length == 3) {
                                            final Map<String, String> scaleVersions =
                                                    versions.computeIfAbsent(
                                                            distName,
                                                            s -> new ConcurrentHashMap<>());
                                            scaleVersions.put(
                                                    String.format(
                                                            "%s-%s",
                                                            regions[0],
                                                            regions[1]),
                                                    regions[2]);
                                        }
                                    }
                                });
                    }
                });

        LOG.info("Currently installed versions: {}", versions);

        return versions;
    }

    /**
     * Returns whether or not the provided folder name is a Foreman release.
     *
     * @param fileName The file name.
     *
     * @return Whether or not the provided folder name is a Foreman folder.
     */
    private static boolean isForeman(final String fileName) {
        return fileName.contains("foreman") ||
                // Could be upgrading self
                fileName.contains("windows-agent");
    }
}
