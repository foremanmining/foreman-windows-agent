package mn.foreman.windowsagent.process;

import mn.foreman.windowsagent.foreman.AppManifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests for {@link WatchDog}. */
class WatchDogTest {

    /** The shared executor used by tests. */
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        this.executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        this.executor.shutdownNow();
    }

    /**
     * Verifies that {@link WatchDog#stopAll()} on a fresh watchdog with no
     * apps does not throw.
     */
    @Test
    void stopAllOnEmptyWatchDog() {
        final WatchDog watchDog = new WatchDog(this.executor);
        assertDoesNotThrow(watchDog::stopAll);
    }

    /**
     * Verifies that {@link WatchDog#stopApp(String, String)} for an unknown
     * dist or app does not throw.
     */
    @Test
    void stopAppForUnknownDistDoesNothing() {
        final WatchDog watchDog = new WatchDog(this.executor);
        assertDoesNotThrow(() -> watchDog.stopApp("nope", "nope"));
    }

    /**
     * Verifies that {@link WatchDog#stopApp(String, String)} removes a known
     * app and invokes {@link App#stop()} on it.
     */
    @Test
    void stopAppRemovesKnownApp() throws Exception {
        final WatchDog watchDog = new WatchDog(this.executor);

        final App mockApp = mock(App.class);
        injectApp(watchDog, "dist", "alias", mockApp);

        watchDog.stopApp("dist", "alias");

        verify(mockApp, times(1)).stop();
        assertNull(getAppMap(watchDog).get("dist").get("alias"));
    }

    /**
     * Verifies that {@link WatchDog#stopAll()} stops every known app across
     * dists.
     */
    @Test
    void stopAllStopsEveryKnownApp() throws Exception {
        final WatchDog watchDog = new WatchDog(this.executor);

        final App appA = mock(App.class);
        final App appB = mock(App.class);
        final App appC = mock(App.class);

        injectApp(watchDog, "distA", "alias-a", appA);
        injectApp(watchDog, "distA", "alias-b", appB);
        injectApp(watchDog, "distB", "alias-c", appC);

        watchDog.stopAll();

        verify(appA).stop();
        verify(appB).stop();
        verify(appC).stop();
    }

    /**
     * Verifies that {@link WatchDog#startApp(String, AppManifest, String)}
     * is a no-op when the app is already running for the given dist/alias
     * combination.
     */
    @Test
    void startAppDoesNotRestartRunningApp() throws Exception {
        final WatchDog watchDog = new WatchDog(this.executor);

        final App existing = mock(App.class);
        when(existing.isNotRunning()).thenReturn(false);
        injectApp(watchDog, "dist", "alias", existing);

        final AppManifest manifest = new AppManifest();
        manifest.alias = "alias";
        manifest.app = "name";
        manifest.executable = "noop";

        watchDog.startApp("dist", manifest, "1.0.0");

        verify(existing, never()).restart();
        assertSame(existing, getAppMap(watchDog).get("dist").get("alias"));
    }

    /**
     * Verifies that {@link WatchDog#startApp(String, AppManifest, String)}
     * restarts an existing app that has stopped (i.e. {@code isNotRunning()}
     * returns {@code true}).
     */
    @Test
    void startAppRestartsKnownStoppedApp() throws Exception {
        final WatchDog watchDog = new WatchDog(this.executor);

        final App existing = mock(App.class);
        when(existing.isNotRunning()).thenReturn(true);
        injectApp(watchDog, "dist", "alias", existing);

        final AppManifest manifest = new AppManifest();
        manifest.alias = "alias";
        manifest.app = "name";
        manifest.executable = "noop";

        watchDog.startApp("dist", manifest, "1.0.0");

        verify(existing, times(1)).restart();
    }

    /**
     * Verifies that {@link WatchDog#startApp(String, AppManifest, String)}
     * fully integrates with a real {@link AppImpl} by forking a real (cheap)
     * process and registering it.  Disabled on Windows because the script is
     * a POSIX shell script.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void startAppForksRealProcess(@TempDir final Path tempDir) throws Exception {
        // Create a real dist layout so FileUtils.toFilePath resolves.
        // Layout: <dist>/<alias>-<version>/bin/<executable>
        final String alias = "foreman-pickaxe";
        final String version = "1.0.0";
        final Path appDir = tempDir.resolve(alias + "-" + version);
        final Path binDir = appDir.resolve("bin");
        Files.createDirectories(binDir);
        final Path script = binDir.resolve("noop.sh");
        Files.write(
                script,
                ("#!/bin/sh\n"
                        + "sleep 30\n").getBytes());
        if (!script.toFile().setExecutable(true)) {
            throw new IllegalStateException("Failed to mark script executable");
        }

        final WatchDog watchDog = new WatchDog(this.executor);

        final AppManifest manifest = new AppManifest();
        manifest.alias = alias;
        manifest.app = "Pickaxe";
        manifest.executable = "noop.sh";

        try {
            watchDog.startApp(tempDir.toString(), manifest, version);

            // The map should now contain the app.
            final Map<String, Map<String, App>> apps = getAppMap(watchDog);
            assertNotNull(apps.get(tempDir.toString()));
            final App app = apps.get(tempDir.toString()).get(alias);
            assertNotNull(app);
            assertEquals("Pickaxe", app.getName());

            // It should have been started by the time we get here.
            TimeUnit.MILLISECONDS.sleep(250);
            assertFalse(app.isNotRunning());
        } finally {
            // Stop in background to skip the 10s sleep.
            stopAllFast(watchDog);
        }
    }

    /**
     * Verifies that the {@code monitor()} scheduled method restarts apps
     * that are currently not running.
     */
    @Test
    void monitorRestartsStoppedApps() throws Exception {
        final WatchDog watchDog = new WatchDog(this.executor);

        final App stopped = mock(App.class);
        when(stopped.isNotRunning()).thenReturn(true);
        when(stopped.getName()).thenReturn("stopped");

        final App running = mock(App.class);
        when(running.isNotRunning()).thenReturn(false);
        when(running.getName()).thenReturn("running");

        injectApp(watchDog, "dist", "stopped-alias", stopped);
        injectApp(watchDog, "dist", "running-alias", running);

        invokeMonitor(watchDog);

        verify(stopped, atLeastOnce()).restart();
        verify(running, never()).restart();
    }

    /**
     * Verifies that {@code monitor()} survives a thrown exception from a
     * single app's {@code restart()}.
     */
    @Test
    void monitorSwallowsRestartExceptions() throws Exception {
        final WatchDog watchDog = new WatchDog(this.executor);

        final App bad = mock(App.class);
        when(bad.isNotRunning()).thenReturn(true);
        when(bad.getName()).thenReturn("bad");
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(bad).restart();

        final App good = mock(App.class);
        when(good.isNotRunning()).thenReturn(true);
        when(good.getName()).thenReturn("good");

        injectApp(watchDog, "dist", "bad-alias", bad);
        injectApp(watchDog, "dist", "good-alias", good);

        assertDoesNotThrow(() -> invokeMonitor(watchDog));

        verify(good, atLeastOnce()).restart();
    }

    /**
     * Pre-populates the watchdog's internal map with a known {@link App}.
     */
    private static void injectApp(
            final WatchDog watchDog,
            final String dist,
            final String alias,
            final App app) throws Exception {
        final Map<String, Map<String, App>> apps = getAppMap(watchDog);
        apps.computeIfAbsent(dist, k -> new HashMap<>()).put(alias, app);
    }

    /** Reads the watchdog's internal {@code apps} map via reflection. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, App>> getAppMap(
            final WatchDog watchDog) throws Exception {
        final Field field = WatchDog.class.getDeclaredField("apps");
        field.setAccessible(true);
        return (Map<String, Map<String, App>>) field.get(watchDog);
    }

    /** Invokes the private {@code monitor()} method via reflection. */
    private static void invokeMonitor(final WatchDog watchDog) throws Exception {
        final Method method = WatchDog.class.getDeclaredMethod("monitor");
        method.setAccessible(true);
        method.invoke(watchDog);
    }

    /**
     * Stops every app in the watchdog on a background thread, interrupting
     * the {@link AppImpl} 10 second post-stop sleep so the test exits
     * quickly.
     */
    private static void stopAllFast(final WatchDog watchDog) throws Exception {
        final Thread stopper = new Thread(watchDog::stopAll);
        stopper.setDaemon(true);
        stopper.start();

        final Map<String, Map<String, App>> apps = getAppMap(watchDog);
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            boolean allStopped = true;
            for (Map<String, App> distApps : apps.values()) {
                for (App app : distApps.values()) {
                    if (!app.isNotRunning()) {
                        allStopped = false;
                        break;
                    }
                }
            }
            if (allStopped) {
                break;
            }
            Thread.sleep(25);
        }

        stopper.interrupt();
        stopper.join(5_000L);
    }
}
