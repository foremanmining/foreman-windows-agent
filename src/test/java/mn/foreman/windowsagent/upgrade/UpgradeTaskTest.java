package mn.foreman.windowsagent.upgrade;

import mn.foreman.windowsagent.VersionFactory;
import mn.foreman.windowsagent.foreman.AppManifest;
import mn.foreman.windowsagent.process.WatchDog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link UpgradeTask}. */
class UpgradeTaskTest {

    /** The manifest endpoint used by these tests. */
    private static final String MANIFEST_URL =
            "https://example.com/api/manifest";

    /** The zip URL used by these tests. */
    private static final String ZIP_URL =
            "https://example.com/foreman-pickaxe-2.0.0.zip";

    /** Mocked rest template. */
    private RestTemplate restTemplate;

    /** Mocked version factory. */
    private VersionFactory versionFactory;

    /** Mocked watchdog. */
    private WatchDog watchDog;

    @BeforeEach
    void setUp() {
        this.restTemplate = mock(RestTemplate.class);
        this.versionFactory = mock(VersionFactory.class);
        this.watchDog = mock(WatchDog.class);
    }

    /**
     * Verifies the full happy-path: a manifest is fetched, a zip is
     * downloaded and unpacked, the conf file has its placeholders replaced,
     * the zip is cleaned up, and the watchdog is asked to start the app.
     */
    @Test
    void installsFreshApp(@TempDir final Path tempDir) throws Exception {
        final AppManifest manifest =
                buildManifest("2.0.0", "REPLACE_API", "REPLACE_ID");
        final byte[] zip =
                buildZip(
                        manifest.alias + "-" + manifest.version,
                        "REPLACE_API and REPLACE_ID inside");

        stubManifestResponse(manifest);
        stubZipResponse(zip);

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        // Manifest should have been GET'd against a URL based on the
        // configured base, with no extra headers (no auth, no client id).
        verify(this.restTemplate).getForObject(
                argThat((String url) -> url != null && url.startsWith(MANIFEST_URL)),
                eq(AppManifest[].class));

        // Zip should have been downloaded
        verify(this.restTemplate).getForObject(ZIP_URL, byte[].class);

        // Files should exist on disk
        final Path identifierDir = tempDir.resolve("0");
        final Path appDir =
                identifierDir.resolve(manifest.alias + "-" + manifest.version);
        assertTrue(Files.isDirectory(appDir));

        // Zip file itself should have been cleaned up
        assertFalse(
                Files.exists(identifierDir.resolve(manifest.github.name)),
                "zip should have been deleted after extraction");

        // Conf should have its placeholders replaced
        final Path confFile = appDir.resolve("conf").resolve(manifest.conf.file);
        assertTrue(Files.isRegularFile(confFile));
        final String confContents =
                Files.readString(confFile);
        assertTrue(
                confContents.contains("MY_API_KEY"),
                "API key should have been substituted: " + confContents);
        assertTrue(
                confContents.contains("client-0"),
                "client id should have been substituted: " + confContents);
        assertFalse(confContents.contains("REPLACE_API"));
        assertFalse(confContents.contains("REPLACE_ID"));

        // Watchdog should have been told to stop the app before upgrade and
        // then start it once installed.
        final String dist = tempDir + File.separator + "0";
        verify(this.watchDog).stopApp(dist, manifest.alias);
        verify(this.watchDog).startApp(dist, manifest, manifest.version);
    }

    /**
     * Verifies that nothing is downloaded or installed when the manifest's
     * version matches the version already on disk and the existing conf is
     * already configured.
     */
    @Test
    void skipsUpgradeWhenAlreadyOnLatestVersion(@TempDir final Path tempDir) throws Exception {
        final AppManifest manifest =
                buildManifest("1.5.0", "REPLACE_API", "REPLACE_ID");

        // Pre-populate dist with the matching version + a clean conf
        final Path identifierDir = tempDir.resolve("0");
        final Path appDir =
                identifierDir.resolve(manifest.alias + "-" + manifest.version);
        final Path confDir = appDir.resolve("conf");
        Files.createDirectories(confDir);
        Files.write(
                confDir.resolve(manifest.conf.file),
                "apiKey=ABC\nclientId=DEF".getBytes(StandardCharsets.UTF_8));

        stubManifestResponse(manifest);

        final Map<String, Map<String, String>> versions = new HashMap<>();
        final Map<String, String> distVersions = new HashMap<>();
        distVersions.put(manifest.alias, manifest.version);
        versions.put("0", distVersions);
        when(this.versionFactory.getVersions()).thenReturn(versions);

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        verify(this.restTemplate, never()).getForObject(anyString(), eq(byte[].class));
        // No stop/upgrade should have happened
        verify(this.watchDog, never()).stopApp(anyString(), eq(manifest.alias));
        // Watchdog should still have been asked to start (the app should be
        // running even if no upgrade is needed)
        verify(this.watchDog, times(1)).startApp(
                tempDir.toString() + File.separator + "0",
                manifest,
                manifest.version);
    }

    /**
     * Verifies that an existing-but-bad configuration triggers a re-upgrade
     * even when the version on disk matches the manifest's version.
     */
    @Test
    void reupgradesWhenConfStillContainsPlaceholders(@TempDir final Path tempDir) throws Exception {
        final AppManifest manifest =
                buildManifest("3.0.0", "REPLACE_API", "REPLACE_ID");

        // Pre-populate dist with the matching version, but a conf that still
        // contains the placeholder patterns (i.e. it never got configured)
        final Path identifierDir = tempDir.resolve("0");
        final Path appDir =
                identifierDir.resolve(manifest.alias + "-" + manifest.version);
        final Path confDir = appDir.resolve("conf");
        Files.createDirectories(confDir);
        Files.write(
                confDir.resolve(manifest.conf.file),
                "apiKey=REPLACE_API\nclientId=REPLACE_ID"
                        .getBytes(StandardCharsets.UTF_8));

        // The zip is for the same version but contains a fresh conf
        final byte[] zip =
                buildZip(
                        manifest.alias + "-" + manifest.version,
                        "apiKey=REPLACE_API\nclientId=REPLACE_ID\nfresh=yes");

        stubManifestResponse(manifest);
        stubZipResponse(zip);

        final Map<String, Map<String, String>> versions = new HashMap<>();
        final Map<String, String> distVersions = new HashMap<>();
        distVersions.put(manifest.alias, manifest.version);
        versions.put("0", distVersions);
        when(this.versionFactory.getVersions()).thenReturn(versions);

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        // Should have downloaded the zip (re-upgrade)
        verify(this.restTemplate, times(1)).getForObject(ZIP_URL, byte[].class);
        // Conf should be the new one, with placeholders replaced
        final Path confFile = appDir.resolve("conf").resolve(manifest.conf.file);
        final String confContents = Files.readString(confFile);
        assertTrue(confContents.contains("MY_API_KEY"));
        assertTrue(confContents.contains("client-0"));
        // Watchdog stop + start
        verify(this.watchDog).stopApp(
                tempDir.toString() + File.separator + "0", manifest.alias);
        verify(this.watchDog).startApp(
                tempDir.toString() + File.separator + "0",
                manifest,
                manifest.version);
    }

    /**
     * Verifies that, when an existing app no longer appears in the manifest,
     * the watchdog is asked to stop it.
     */
    @Test
    void stopsAppsThatHaveBeenRemovedFromManifest(@TempDir final Path tempDir) {
        final AppManifest manifest =
                buildManifest("2.0.0", "REPLACE_API", "REPLACE_ID");

        // Manifest only references "foreman-pickaxe", but versionFactory
        // reports an extra installed app called "foreman-old".
        stubManifestResponse(manifest);

        final byte[] zip;
        try {
            zip = buildZip(
                    manifest.alias + "-" + manifest.version,
                    "REPLACE_API REPLACE_ID");
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        stubZipResponse(zip);

        final Map<String, Map<String, String>> versions = new HashMap<>();
        final Map<String, String> distVersions = new HashMap<>();
        distVersions.put("foreman-old", "0.0.1");
        versions.put("0", distVersions);
        when(this.versionFactory.getVersions()).thenReturn(versions);

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        // The "foreman-old" app should have been stopped because it is
        // no longer referenced by the manifest
        verify(this.watchDog).stopApp(
                tempDir.toString() + File.separator + "0",
                "foreman-old");
    }

    /**
     * Verifies that non-windows manifests are filtered out and nothing is
     * downloaded for them.
     */
    @Test
    void skipsNonWindowsManifests(@TempDir final Path tempDir) throws Exception {
        final AppManifest manifest =
                buildManifest("2.0.0", "REPLACE_API", "REPLACE_ID");
        manifest.windows = false;

        stubManifestResponse(manifest);
        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        verify(this.restTemplate, never()).getForObject(anyString(), eq(byte[].class));
        verify(this.watchDog, never()).startApp(anyString(), any(), anyString());
    }

    /**
     * Verifies that, when no pickaxe install exists for an identifier (so
     * we have no key to send), the manifest URL is GET'd unchanged - the
     * {@code pickaxe_key} query parameter is dropped entirely rather than
     * sent as an empty value.  The GET is a plain {@code getForObject} with
     * no headers, no auth, no other interactions.
     */
    @Test
    void noPickaxeKeyOmitsQueryParamEntirely(@TempDir final Path tempDir) {
        stubManifestResponse();

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        verify(this.restTemplate, times(1)).getForObject(
                MANIFEST_URL,
                AppManifest[].class);
        verifyNoMoreInteractions(this.restTemplate);
    }

    /**
     * Verifies that a {@code null} manifest response body is handled
     * gracefully (no exceptions, no follow-up downloads).
     */
    @Test
    void nullManifestBodyIsTolerated(@TempDir final Path tempDir) throws Exception {
        when(this.restTemplate.getForObject(
                argThat((String url) -> url != null && url.startsWith(MANIFEST_URL)),
                eq(AppManifest[].class)))
                .thenReturn(null);

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        verify(this.restTemplate, never()).getForObject(ZIP_URL, byte[].class);
        verify(this.watchDog, never()).startApp(anyString(), any(), anyString());
    }

    /**
     * Verifies that, when the zip download returns {@code null}, no upgrade
     * is performed (the existing install remains untouched).
     */
    @Test
    void missingZipDoesNotCorruptState(@TempDir final Path tempDir) throws Exception {
        final AppManifest manifest =
                buildManifest("2.0.0", "REPLACE_API", "REPLACE_ID");

        stubManifestResponse(manifest);

        when(this.restTemplate.getForObject(ZIP_URL, byte[].class))
                .thenReturn(null);

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        // Stop is called as part of upgrade(), but no app folder should
        // have been created.
        verify(this.watchDog, times(1)).stopApp(
                tempDir.toString() + File.separator + "0",
                manifest.alias);
        // No conf folder since extraction never happened
        assertFalse(
                Files.exists(
                        tempDir.resolve("0")
                                .resolve(manifest.alias + "-" + manifest.version)));
        // startApp is still attempted - with a null version (the old version)
        verify(this.watchDog, times(1)).startApp(
                eq(tempDir.toString() + File.separator + "0"),
                eq(manifest),
                org.mockito.ArgumentMatchers.isNull());
    }

    /**
     * Verifies that an existing prior version directory is wiped before a
     * new version is unpacked.
     */
    @Test
    void deletesPriorVersionsOnUpgrade(@TempDir final Path tempDir) throws Exception {
        final AppManifest manifest =
                buildManifest("2.0.0", "REPLACE_API", "REPLACE_ID");

        // Pre-create the OLD version folder with a sentinel file
        final Path identifierDir = tempDir.resolve("0");
        final Path oldAppDir =
                identifierDir.resolve(manifest.alias + "-1.0.0");
        Files.createDirectories(oldAppDir);
        Files.write(
                oldAppDir.resolve("sentinel.txt"),
                "this should be deleted".getBytes(StandardCharsets.UTF_8));

        final byte[] zip =
                buildZip(
                        manifest.alias + "-" + manifest.version,
                        "REPLACE_API REPLACE_ID");

        stubManifestResponse(manifest);
        stubZipResponse(zip);

        final Map<String, Map<String, String>> versions = new HashMap<>();
        final Map<String, String> distVersions = new HashMap<>();
        distVersions.put(manifest.alias, "1.0.0");
        versions.put("0", distVersions);
        when(this.versionFactory.getVersions()).thenReturn(versions);

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        assertFalse(
                Files.exists(oldAppDir),
                "old version directory should have been deleted");
        assertTrue(
                Files.isDirectory(
                        identifierDir.resolve(manifest.alias + "-" + manifest.version)),
                "new version directory should exist");
    }

    /**
     * Verifies that, on upgrade, the prior conf is preserved (the new
     * extracted conf is replaced with the old one before the placeholders
     * are substituted).
     */
    @Test
    void preservesPreviousConfOnUpgrade(@TempDir final Path tempDir) throws Exception {
        final AppManifest manifest =
                buildManifest("2.0.0", "REPLACE_API", "REPLACE_ID");

        // Pre-existing 1.0.0 install with custom conf
        final Path identifierDir = tempDir.resolve("0");
        final Path oldAppDir =
                identifierDir.resolve(manifest.alias + "-1.0.0");
        final Path oldConfDir = oldAppDir.resolve("conf");
        Files.createDirectories(oldConfDir);
        Files.write(
                oldConfDir.resolve(manifest.conf.file),
                ("custom=preserved\napiKey=ABC\nclientId=XYZ")
                        .getBytes(StandardCharsets.UTF_8));

        // The zip's conf has placeholders that the test expects NOT to be
        // used (because the old conf wins).
        final byte[] zip =
                buildZip(
                        manifest.alias + "-" + manifest.version,
                        "default=fresh\napiKey=REPLACE_API\nclientId=REPLACE_ID");

        stubManifestResponse(manifest);
        stubZipResponse(zip);

        final Map<String, Map<String, String>> versions = new HashMap<>();
        final Map<String, String> distVersions = new HashMap<>();
        distVersions.put(manifest.alias, "1.0.0");
        versions.put("0", distVersions);
        when(this.versionFactory.getVersions()).thenReturn(versions);

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        final Path newConfFile =
                identifierDir
                        .resolve(manifest.alias + "-" + manifest.version)
                        .resolve("conf")
                        .resolve(manifest.conf.file);
        final String contents = Files.readString(newConfFile);

        assertTrue(
                contents.contains("custom=preserved"),
                "old conf should have been preserved: " + contents);
        // The old conf had no REPLACE_API/REPLACE_ID placeholders, so the
        // existing literal values should remain.
        assertTrue(contents.contains("apiKey=ABC"));
        assertTrue(contents.contains("clientId=XYZ"));
        assertFalse(
                contents.contains("default=fresh"),
                "fresh conf from the zip should NOT have been used");
    }

    /**
     * Verifies that the {@code clientIdSupplier} receives the per-identifier
     * value when running for multiple identifiers.
     */
    @Test
    void clientIdSupplierIsCalledPerIdentifier(@TempDir final Path tempDir) throws Exception {
        final AppManifest manifest =
                buildManifest("2.0.0", "REPLACE_API", "REPLACE_ID");
        final byte[] zip =
                buildZip(
                        manifest.alias + "-" + manifest.version,
                        "REPLACE_ID");

        stubManifestResponse(manifest);
        stubZipResponse(zip);

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final Map<String, String> seenIdentifiers = new LinkedHashMap<>();
        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Arrays.asList(7, 9),
                        identifier -> {
                            seenIdentifiers.put(identifier, "client-" + identifier);
                            return "client-" + identifier;
                        },
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        // Both identifiers should have been processed
        assertTrue(seenIdentifiers.containsKey("7"));
        assertTrue(seenIdentifiers.containsKey("9"));

        // Both folders should exist on disk with their per-identifier
        // configuration written out
        final Path conf7 = tempDir.resolve("7")
                .resolve(manifest.alias + "-" + manifest.version)
                .resolve("conf").resolve(manifest.conf.file);
        final Path conf9 = tempDir.resolve("9")
                .resolve(manifest.alias + "-" + manifest.version)
                .resolve("conf").resolve(manifest.conf.file);

        assertTrue(Files.exists(conf7));
        assertTrue(Files.exists(conf9));

        assertTrue(
                Files.readString(conf7)
                        .contains("client-7"));
        assertTrue(
                Files.readString(conf9)
                        .contains("client-9"));
    }

    /**
     * Verifies that the per-identifier pickaxe key found in
     * {@code <agentDist>/<identifier>/foreman-pickaxe-*\/conf/pickaxe.yml}
     * is appended to the manifest URL as {@code pickaxe_key=...}.
     */
    @Test
    void appendsPickaxeKeyFromIdentifierDist(@TempDir final Path tempDir) {
        installPickaxeYml(tempDir, "0", "foreman-pickaxe-1.0.0", "secret-key");

        stubManifestResponse();

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        verify(this.restTemplate, times(1)).getForObject(
                MANIFEST_URL + "?pickaxe_key=secret-key",
                AppManifest[].class);
    }

    /**
     * Verifies that, with multiple identifiers, each one resolves and uses
     * its own pickaxe key (i.e. one identifier's key never leaks into
     * another identifier's manifest URL).
     */
    @Test
    void usesPerIdentifierPickaxeKey(@TempDir final Path tempDir) {
        installPickaxeYml(tempDir, "7", "foreman-pickaxe-1.0.0", "key-seven");
        installPickaxeYml(tempDir, "9", "foreman-pickaxe-1.0.0", "key-nine");

        stubManifestResponse();

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Arrays.asList(7, 9),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        verify(this.restTemplate, times(1)).getForObject(
                MANIFEST_URL + "?pickaxe_key=key-seven",
                AppManifest[].class);
        verify(this.restTemplate, times(1)).getForObject(
                MANIFEST_URL + "?pickaxe_key=key-nine",
                AppManifest[].class);
    }

    /**
     * Verifies that the pickaxe key value is URL-encoded when embedded into
     * the manifest URL.
     */
    @Test
    void urlEncodesPickaxeKey(@TempDir final Path tempDir) {
        installPickaxeYml(tempDir, "0", "foreman-pickaxe-1.0.0", "abc def&xyz");

        stubManifestResponse();

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        final org.mockito.ArgumentCaptor<String> urlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(this.restTemplate).getForObject(
                urlCaptor.capture(),
                eq(AppManifest[].class));

        final String url = urlCaptor.getValue();
        assertFalse(url.contains("abc def&xyz"), "raw value should not appear: " + url);
        assertTrue(url.contains("abc+def") || url.contains("abc%20def"), url);
        assertTrue(url.contains("%26"), "ampersand should be percent-encoded in: " + url);
    }

    /**
     * Verifies that, when the configured base URL already has a query
     * string, {@code pickaxe_key} is appended with {@code &} instead of
     * {@code ?}.
     */
    @Test
    void appendsWithAmpersandWhenBaseUrlHasQuery(@TempDir final Path tempDir) {
        installPickaxeYml(tempDir, "0", "foreman-pickaxe-1.0.0", "the-key");

        final String baseWithQuery = MANIFEST_URL + "?foo=bar";
        when(this.restTemplate.getForObject(
                argThat((String url) -> url != null && url.startsWith(baseWithQuery)),
                eq(AppManifest[].class)))
                .thenReturn(new AppManifest[0]);

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        baseWithQuery,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        verify(this.restTemplate).getForObject(
                baseWithQuery + "&pickaxe_key=the-key",
                AppManifest[].class);
    }

    /**
     * Verifies that a malformed (non-mapping) {@code pickaxe.yml} is
     * silently tolerated and treated as no key (so the
     * {@code pickaxe_key} query parameter is omitted entirely).
     */
    @Test
    void malformedPickaxeYmlIsTolerated(@TempDir final Path tempDir) throws Exception {
        final Path scaleDir = tempDir.resolve("0");
        final Path pickaxeDir = scaleDir.resolve("foreman-pickaxe-1.0.0");
        final Path confDir = pickaxeDir.resolve("conf");
        Files.createDirectories(confDir);
        Files.write(
                confDir.resolve("pickaxe.yml"),
                "just a string".getBytes(StandardCharsets.UTF_8));

        stubManifestResponse();

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        verify(this.restTemplate).getForObject(
                MANIFEST_URL,
                AppManifest[].class);
    }

    /**
     * Verifies that an empty {@code pickaxeId} field in {@code pickaxe.yml}
     * is treated as no key (so the {@code pickaxe_key} query parameter is
     * omitted entirely).
     */
    @Test
    void emptyPickaxeIdOmitsQueryParam(@TempDir final Path tempDir) {
        installPickaxeYml(tempDir, "0", "foreman-pickaxe-1.0.0", "");

        stubManifestResponse();

        when(this.versionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());

        final UpgradeTask task =
                new UpgradeTask(
                        MANIFEST_URL,
                        tempDir.toString(),
                        Collections.singletonList(0),
                        identifier -> "client-" + identifier,
                        "MY_API_KEY",
                        this.versionFactory,
                        this.watchDog,
                        this.restTemplate);

        task.check();

        verify(this.restTemplate).getForObject(
                MANIFEST_URL,
                AppManifest[].class);
    }

    /**
     * Writes a {@code pickaxe.yml} file with the given {@code pickaxeId} at
     * {@code <agentDist>/<identifier>/<appName>/conf/pickaxe.yml}.
     */
    private static void installPickaxeYml(
            final Path agentDist,
            final String identifier,
            final String appName,
            final String pickaxeId) {
        try {
            final Path confDir =
                    agentDist
                            .resolve(identifier)
                            .resolve(appName)
                            .resolve("conf");
            Files.createDirectories(confDir);
            Files.write(
                    confDir.resolve("pickaxe.yml"),
                    ("pickaxeId: \"" + pickaxeId + "\"\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Builds a simple manifest fixture. */
    private static AppManifest buildManifest(
            final String version,
            final String apiKeyPattern,
            final String clientIdPattern) {
        final AppManifest manifest = new AppManifest();
        manifest.alias = "foreman-pickaxe";
        manifest.app = "Foreman Pickaxe";
        manifest.executable = "pickaxe.bat";
        manifest.version = version;
        manifest.windows = true;

        final AppManifest.Github github = new AppManifest.Github();
        github.name = manifest.alias + "-" + version + ".zip";
        github.zipUrl = ZIP_URL;
        manifest.github = github;

        final AppManifest.Conf conf = new AppManifest.Conf();
        conf.file = "pickaxe.yml";
        conf.apiKeyPattern = apiKeyPattern;
        conf.clientIdPattern = clientIdPattern;
        manifest.conf = conf;

        return manifest;
    }

    /**
     * Builds a zip file that contains a single app folder with a {@code conf}
     * subfolder containing the conf file and a {@code bin} subfolder
     * containing the executable.
     */
    private static byte[] buildZip(
            final String topLevel,
            final String confContents) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            zos.putNextEntry(new ZipEntry(topLevel + "/"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(topLevel + "/conf/"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(topLevel + "/conf/pickaxe.yml"));
            zos.write(confContents.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(topLevel + "/bin/"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(topLevel + "/bin/pickaxe.bat"));
            zos.write("@echo off\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.finish();
            return baos.toByteArray();
        }
    }

    /**
     * Stubs every manifest GET (any URL that starts with {@link
     * #MANIFEST_URL}, including ones with the {@code ?pickaxe_key=...}
     * query parameter appended by {@link UpgradeTask}).
     */
    private void stubManifestResponse(final AppManifest... manifests) {
        when(this.restTemplate.getForObject(
                argThat((String url) -> url != null && url.startsWith(MANIFEST_URL)),
                eq(AppManifest[].class)))
                .thenReturn(manifests);
    }

    /** Stubs the zip download URL response. */
    private void stubZipResponse(final byte[] zip) {
        when(this.restTemplate.getForObject(ZIP_URL, byte[].class))
                .thenReturn(zip);
    }
}
