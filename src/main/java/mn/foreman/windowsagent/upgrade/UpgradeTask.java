package mn.foreman.windowsagent.upgrade;

import mn.foreman.windowsagent.AppFolder;
import mn.foreman.windowsagent.foreman.AppManifest;
import mn.foreman.windowsagent.process.WatchDog;

import net.lingala.zip4j.ZipFile;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Continuously polls the <code>/api/manifest</code> endpoint, looking for
 * applications to install.
 */
@Component
public class UpgradeTask {

    /** The logger for this class. */
    private static final Logger LOG =
            LoggerFactory.getLogger(UpgradeTask.class);

    /** Where the agent lives. */
    private final String agentDist;

    /** The API key. */
    private final String apiKey;

    /** The link to the apps manifest. */
    private final String appsManifest;

    /** The client ID. */
    private final String clientId;

    /** The template for HTTP operations. */
    private final RestTemplate restTemplate;

    /** The installed versions. */
    private final Map<String, String> versions;

    /** The monitor for starting and stopping apps. */
    private final WatchDog watchDog;

    /**
     * Constructor.
     *
     * @param appsManifest The manifest URL.
     * @param agentDist    The agent dist.
     * @param clientId     The client ID.
     * @param apiKey       The API key.
     * @param versions     The installed versions.
     * @param watchDog     The monitor for starting and stopping apps.
     * @param restTemplate The template for HTTP operations.
     */
    @Autowired
    public UpgradeTask(
            @Value("${apps.manifest}") final String appsManifest,
            @Value("${agent.dist}") final String agentDist,
            @Value("${client.id}") final String clientId,
            @Value("${client.apiKey}") final String apiKey,
            final Map<String, String> versions,
            final WatchDog watchDog,
            final RestTemplate restTemplate) {
        this.appsManifest = appsManifest;
        this.agentDist = agentDist;
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.versions = versions;
        this.watchDog = watchDog;
        this.restTemplate = restTemplate;
    }

    /** Checks for an upgrade. */
    @Scheduled(fixedRateString = "${upgrade.check.frequency}")
    public void check() {
        // Determine which applications are being auto-upgraded
        final AppManifest[] appManifests =
                this.restTemplate.getForObject(
                        this.appsManifest,
                        AppManifest[].class);

        // Check and upgrade each, as needed
        if (appManifests != null) {
            final Set<String> extraApps =
                    new HashSet<>(this.versions.keySet());

            Arrays
                    .stream(appManifests)
                    .forEach(manifest -> {
                        try {
                            extraApps.remove(manifest.alias);
                            checkAndUpgrade(manifest);
                        } catch (final Exception e) {
                            LOG.warn("Exception occurred", e);
                        }
                    });

            extraApps.forEach(app -> {
                try {
                    this.watchDog.stopApp(app);
                } catch (final Exception e) {
                    LOG.warn("Failed to stop app", e);
                }
            });
        } else {
            LOG.warn("Failed to obtain app manifests from {}", this.appsManifest);
        }
    }

    /**
     * Checks to see if the provided pattern is present in the file.
     *
     * @param pattern The pattern.
     * @param conf    The conf contents.
     *
     * @return Whether or not the pattern is present.
     */
    private static boolean patternIsPresent(
            final String pattern,
            final String conf) {
        return (pattern != null && !pattern.isEmpty() && conf.contains(pattern));
    }

    /**
     * Checks to see if, based on the current version of the application
     * installed, an upgrade is needed.
     *
     * @param currentVersion The current version.
     * @param newVersion     The latest version.
     *
     * @return Whether or not an upgrade is needed.
     */
    private static boolean shouldUpgrade(
            final String currentVersion,
            final String newVersion) {
        return ((currentVersion == null) || !currentVersion.equals(newVersion));
    }

    /**
     * Checks to see if an upgrade is needed and upgrades, if applicable.
     *
     * @param manifest The manifest.
     *
     * @throws Exception on failure.
     */
    private void checkAndUpgrade(
            final AppManifest manifest)
            throws Exception {
        final String currentVersion =
                this.versions.get(manifest.alias);
        final String latestVersion =
                manifest.version;
        if (shouldUpgrade(currentVersion, latestVersion) || confIsBad(manifest, currentVersion)) {
            upgrade(
                    manifest,
                    currentVersion);
        } else {
            LOG.info("Already have the latest version");
        }

        // Always start the app (could be the first run, or was just upgraded)
        this.watchDog.startApp(
                manifest,
                this.versions.get(manifest.alias));
    }

    /**
     * Checks to see if the conf file looks invalid.
     *
     * @param manifest The manifest.
     * @param version  The current version.
     *
     * @return Whether or not the conf file looks invalid.
     */
    private boolean confIsBad(
            final AppManifest manifest,
            final String version) {
        final String confContents =
                readConf(
                        manifest,
                        version);
        final AppManifest.Conf manifestConf = manifest.conf;
        return patternIsPresent(manifestConf.apiKeyPattern, confContents) ||
                patternIsPresent(manifestConf.clientIdPattern, confContents);
    }

    /**
     * Downloads the release as bytes.
     *
     * @param url The release asset.
     *
     * @return The bytes.
     */
    private Optional<byte[]> downloadRelease(final String url) {
        return Optional.ofNullable(
                this.restTemplate.getForObject(
                        url,
                        byte[].class));
    }

    /**
     * Reads the current configuration.
     *
     * @param manifest       The manifest.
     * @param currentVersion The current version.
     *
     * @return The configuration.
     */
    private String readConf(
            final AppManifest manifest,
            final String currentVersion) {
        String conf = null;

        try {
            if (manifest.conf != null && currentVersion != null && !currentVersion.isEmpty()) {
                final Path oldConfFile =
                        mn.foreman.windowsagent.FileUtils.toFilePath(
                                this.agentDist,
                                manifest,
                                currentVersion,
                                AppFolder.CONF,
                                manifest.conf.file);
                conf =
                        new String(
                                Files.readAllBytes(
                                        oldConfFile));
            }
        } catch (final Exception e) {
            LOG.warn("Failed to read previous conf file for {}",
                    manifest,
                    currentVersion);
        }

        return conf;
    }

    /**
     * Upgrades the application, using the provided file contents as the zip
     * archive.
     *
     * @param manifest    The manifest.
     * @param fileName    The file name.
     * @param version     The version.
     * @param oldConf     The old conf contents.
     * @param zipContents The contents.
     *
     * @throws IOException on failure to upgrade.
     */
    private void runUpgrade(
            final AppManifest manifest,
            final String fileName,
            final String version,
            final String oldConf,
            final byte[] zipContents) throws IOException {
        // Save the file to disk
        final File newZipFile = new File(this.agentDist + File.separator + fileName);
        LOG.info("Writing release to disk: {}", newZipFile);
        final Path path = Paths.get(newZipFile.toURI());
        Files.deleteIfExists(path);
        Files.write(path, zipContents);

        // Delete the old versions
        mn.foreman.windowsagent.FileUtils.forFileIn(
                this.agentDist,
                File::isDirectory,
                file -> {
                    if (file.getName().contains(manifest.alias)) {
                        try {
                            FileUtils.deleteDirectory(file);
                        } catch (final IOException e) {
                            LOG.warn("Failed to delete directory", e);
                        }
                    }
                });

        // Unzip
        LOG.info("Unzipping {} to {}", newZipFile, this.agentDist);
        final ZipFile zipFile = new ZipFile(newZipFile);
        zipFile.extractAll(this.agentDist);

        // Configure
        final AppManifest.Conf conf = manifest.conf;
        if (conf != null) {
            final Path confFile =
                    mn.foreman.windowsagent.FileUtils.toFilePath(
                            this.agentDist,
                            manifest,
                            version,
                            AppFolder.CONF,
                            conf.file);

            String confContents;
            if (oldConf != null && !oldConf.isEmpty()) {
                confContents = oldConf;
            } else {
                confContents = new String(Files.readAllBytes(confFile));
            }
            confContents =
                    confContents.replace(
                            conf.apiKeyPattern,
                            this.apiKey);
            confContents =
                    confContents.replace(
                            conf.clientIdPattern,
                            this.clientId);

            Files.write(
                    confFile,
                    confContents.getBytes());
        }

        if (!newZipFile.delete()) {
            LOG.warn("Failed to delete zip: {}", newZipFile);
        }

        this.versions.put(manifest.alias, version);
    }

    /**
     * Performs an upgrade by downloading and unpacking the zip from github.
     *
     * @param manifest       The manifest.
     * @param currentVersion The current version that's installed.
     *
     * @throws Exception on failure to upgrade.
     */
    private void upgrade(
            final AppManifest manifest,
            final String currentVersion) throws Exception {
        LOG.info("Stopping {}", manifest.app);
        this.watchDog.stopApp(manifest.alias);

        final Optional<byte[]> newVersionsZip =
                downloadRelease(manifest.github.zipUrl);
        if (newVersionsZip.isPresent()) {
            runUpgrade(
                    manifest,
                    manifest.github.name,
                    manifest.version,
                    readConf(
                            manifest,
                            currentVersion),
                    newVersionsZip.get());
        } else {
            LOG.warn("Failed to obtain the release");
        }
    }
}