package mn.foreman.windowsagent;

import java.util.Map;

/** Creates a {@link Map} containing all of the installed versions, */
public interface VersionFactory {

    /**
     * Creates a {@link Map} containing all of the installed application
     * versions.
     *
     * @return The versions.
     */
    Map<String, Map<String, String>> getVersions();
}
