package mn.foreman.windowsagent.upgrade;

import mn.foreman.api.ForemanApi;
import mn.foreman.api.endpoints.groups.Groups;
import mn.foreman.windowsagent.VersionFactory;
import mn.foreman.windowsagent.foreman.AppManifest;
import mn.foreman.windowsagent.process.WatchDog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Upgrader}.
 *
 * <p>{@link Upgrader} is just an orchestrator - it builds {@link
 * UpgradeTask} instances and runs them.  Manifest URL building, pickaxe key
 * resolution and conf handling all live in {@link UpgradeTask}.  These tests
 * therefore exercise only the orchestration concerns: how many tasks are
 * built (private vs. colo), which identifiers each task receives, and when
 * the foreman API is consulted for sub-clients.
 */
class UpgraderTest {

    /** The base manifest URL passed in via configuration. */
    private static final String BASE_MANIFEST_URL =
            "https://example.com/api/manifest";

    /** Mocks. */
    private RestTemplate restTemplate;
    private VersionFactory privateVersionFactory;
    private VersionFactory coloVersionFactory;
    private ForemanApi foremanApi;
    private WatchDog watchDog;

    @BeforeEach
    void setUp() {
        this.restTemplate = mock(RestTemplate.class);
        this.privateVersionFactory = mock(VersionFactory.class);
        this.coloVersionFactory = mock(VersionFactory.class);
        this.foremanApi = mock(ForemanApi.class);
        this.watchDog = mock(WatchDog.class);

        when(this.privateVersionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());
        when(this.coloVersionFactory.getVersions())
                .thenReturn(new ConcurrentHashMap<>());
        when(this.restTemplate.getForObject(
                anyString(),
                eq(AppManifest[].class)))
                .thenReturn(new AppManifest[0]);
    }

    /**
     * Verifies that, when colo is disabled, only the private task runs
     * (one manifest GET per private identifier) and the colo API is never
     * consulted.
     */
    @Test
    void coloDisabledRunsOnlyPrivateTask(@TempDir final Path tempDir) {
        final int scale = 3;
        final Upgrader upgrader =
                newUpgrader(tempDir, scale, /* coloEnabled */ false);

        upgrader.check();

        // One manifest GET per private identifier, no colo work
        verify(this.restTemplate, times(scale)).getForObject(
                anyString(), eq(AppManifest[].class));
        verify(this.privateVersionFactory, times(1)).getVersions();
        verify(this.coloVersionFactory, never()).getVersions();
        verifyNoInteractions(this.foremanApi);
    }

    /**
     * Verifies that, with colo enabled and sub-clients returned, the colo
     * task runs in addition to the private task: one manifest GET per
     * private identifier plus one per sub-client.
     */
    @Test
    void coloEnabledFetchesSubClientsAndIssuesAdditionalChecks(@TempDir final Path tempDir) {
        final Groups groups = mock(Groups.class);
        final Groups.GroupInfo info = new Groups.GroupInfo();
        final Groups.GroupInfo.SubClient subA = new Groups.GroupInfo.SubClient();
        subA.id = 11;
        final Groups.GroupInfo.SubClient subB = new Groups.GroupInfo.SubClient();
        subB.id = 12;
        info.subClients = Arrays.asList(subA, subB);

        when(groups.groups()).thenReturn(Optional.of(info));
        when(this.foremanApi.groups()).thenReturn(groups);

        final int scale = 1;
        final Upgrader upgrader =
                newUpgrader(tempDir, scale, /* coloEnabled */ true);

        upgrader.check();

        // 1 private GET + 2 colo GETs = 3 total
        verify(this.restTemplate, times(scale + info.subClients.size()))
                .getForObject(anyString(), eq(AppManifest[].class));

        verify(this.privateVersionFactory, times(1)).getVersions();
        verify(this.coloVersionFactory, times(1)).getVersions();
        verify(this.foremanApi, times(1)).groups();
    }

    /**
     * Verifies that, with colo enabled but no sub-clients, the colo task is
     * not run (no extra manifest GET, colo version factory not consulted).
     */
    @Test
    void coloEnabledWithoutSubClientsSkipsColoTask(@TempDir final Path tempDir) {
        final Groups groups = mock(Groups.class);
        when(groups.groups()).thenReturn(Optional.empty());
        when(this.foremanApi.groups()).thenReturn(groups);

        final int scale = 2;
        final Upgrader upgrader =
                newUpgrader(tempDir, scale, /* coloEnabled */ true);

        upgrader.check();

        verify(this.restTemplate, times(scale)).getForObject(
                anyString(), eq(AppManifest[].class));
        verify(this.coloVersionFactory, never()).getVersions();
        verify(this.foremanApi, times(1)).groups();
    }

    /**
     * Verifies that, with colo enabled and a {@code GroupInfo} that has a
     * {@code null} sub-clients list, no colo task is run.
     */
    @Test
    void coloEnabledWithNullSubClientsListIsTolerated(@TempDir final Path tempDir) {
        final Groups groups = mock(Groups.class);
        final Groups.GroupInfo info = new Groups.GroupInfo();
        info.subClients = null;

        when(groups.groups()).thenReturn(Optional.of(info));
        when(this.foremanApi.groups()).thenReturn(groups);

        final Upgrader upgrader =
                newUpgrader(tempDir, /* scale */ 1, /* coloEnabled */ true);

        upgrader.check();

        verify(this.restTemplate, times(1)).getForObject(
                anyString(), eq(AppManifest[].class));
        verify(this.coloVersionFactory, never()).getVersions();
    }

    /**
     * Verifies that the manifest URL passed in via configuration is used as
     * the base for outgoing requests (i.e. it is not modified by {@link
     * Upgrader} itself - any modification is the {@link UpgradeTask}'s
     * responsibility).
     */
    @Test
    void manifestUrlIsPassedThroughToTasks(@TempDir final Path tempDir) {
        final Upgrader upgrader =
                newUpgrader(tempDir, /* scale */ 1, /* coloEnabled */ false);

        upgrader.check();

        final org.mockito.ArgumentCaptor<String> urlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(this.restTemplate).getForObject(
                urlCaptor.capture(),
                eq(AppManifest[].class));

        // The URL the task fetches is the base + whatever query params it
        // wants to add - but it must always start with the base URL.
        org.junit.jupiter.api.Assertions.assertTrue(
                urlCaptor.getValue().startsWith(BASE_MANIFEST_URL),
                urlCaptor.getValue());
    }

    /** Convenience for building an {@link Upgrader} with the test mocks. */
    private Upgrader newUpgrader(
            final Path tempDir,
            final int scale,
            final boolean coloEnabled) {
        return new Upgrader(
                BASE_MANIFEST_URL,
                "client-id",
                "API_KEY",
                tempDir.resolve("private").toString(),
                scale,
                coloEnabled,
                tempDir.resolve("colo").toString(),
                this.privateVersionFactory,
                this.coloVersionFactory,
                this.foremanApi,
                this.watchDog,
                this.restTemplate);
    }
}
