package mn.foreman.windowsagent.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AppImpl}.
 *
 * <p>These tests exercise the actual fork/stop semantics of {@link AppImpl}.
 * They are disabled on Windows because the test scripts use a POSIX shell.
 *
 * <p>{@link AppImpl#stop()} sleeps for ten seconds after killing the process,
 * so all stop calls in this class run on a background thread that we
 * interrupt as soon as we have verified the post-stop state.
 */
@DisabledOnOs(OS.WINDOWS)
class AppImplTest {

    /** Verifies that a fresh app reports as not running. */
    @Test
    void reportsNotRunningInitially(@TempDir final Path tempDir) {
        final ExecutorService executor = Executors.newCachedThreadPool();
        try {
            final AppImpl app =
                    new AppImpl(
                            "test",
                            tempDir.resolve("noop"),
                            executor);

            assertTrue(app.isNotRunning());
            assertEquals("test", app.getName());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Verifies that {@link AppImpl#start()} forks the configured executable
     * and that {@link AppImpl#stop()} terminates it.
     */
    @Test
    void startAndStopForksAndKillsProcess(@TempDir final Path tempDir) throws Exception {
        final Path script = createSleepScript(tempDir);

        final ExecutorService executor = Executors.newCachedThreadPool();
        try {
            final AppImpl app = new AppImpl("sleeper", script, executor);

            app.start();

            TimeUnit.MILLISECONDS.sleep(250);
            assertFalse(app.isNotRunning(), "app should be marked running after start");

            stopFastInBackground(app);
            assertTrue(app.isNotRunning(), "app should be marked stopped after stop");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Verifies that calling {@link AppImpl#start()} repeatedly while running
     * does not produce additional processes (it should be a no-op the second
     * time).
     */
    @Test
    void startWhileRunningIsNoOp(@TempDir final Path tempDir) throws Exception {
        final Path script = createSleepScript(tempDir);

        final ExecutorService executor = Executors.newCachedThreadPool();
        try {
            final AppImpl app = new AppImpl("sleeper", script, executor);

            app.start();
            TimeUnit.MILLISECONDS.sleep(150);
            assertFalse(app.isNotRunning());

            app.start();
            assertFalse(app.isNotRunning());

            stopFastInBackground(app);
            assertTrue(app.isNotRunning());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Verifies that {@link AppImpl#stop()} on an app that was never started
     * is safe (no exception, no sleep) and leaves the app in the not-running
     * state.
     */
    @Test
    void stopWhenNeverStartedIsSafe(@TempDir final Path tempDir) {
        final ExecutorService executor = Executors.newCachedThreadPool();
        try {
            final AppImpl app =
                    new AppImpl(
                            "noop",
                            tempDir.resolve("noop"),
                            executor);

            app.stop();
            assertTrue(app.isNotRunning());
        } finally {
            executor.shutdownNow();
        }
    }

    /** Creates a small POSIX shell script that sleeps when run. */
    private static Path createSleepScript(final Path tempDir) throws Exception {
        final Path script = tempDir.resolve("sleeper.sh");
        Files.write(
                script,
                ("#!/bin/sh\n"
                        + "sleep 30\n").getBytes());
        if (!script.toFile().setExecutable(true)) {
            throw new IllegalStateException("Failed to mark script executable");
        }
        return script;
    }

    /**
     * Runs {@link AppImpl#stop()} on a background thread and then interrupts
     * the thread so we don't have to wait for the hard-coded 10 second
     * post-stop sleep.  Returns once the running flag has been flipped.
     */
    private static void stopFastInBackground(final AppImpl app) throws Exception {
        final Thread stopper = new Thread(app::stop);
        stopper.setDaemon(true);
        stopper.start();

        // Wait for the running flag to flip before we interrupt the sleep
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (!app.isNotRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }

        stopper.interrupt();
        stopper.join(5_000L);
    }
}
