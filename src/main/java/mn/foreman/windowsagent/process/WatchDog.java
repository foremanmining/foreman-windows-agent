package mn.foreman.windowsagent.process;

import mn.foreman.windowsagent.AppFolder;
import mn.foreman.windowsagent.FileUtils;
import mn.foreman.windowsagent.foreman.AppManifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Starts/stops the applications that are being managed by the agent. */
@Component
public class WatchDog {

    /** The logger for this class. */
    private static final Logger LOG =
            LoggerFactory.getLogger(WatchDog.class);

    /** The agent's home directory. */
    private final String agentHome;

    /** The applications currently being monitored. */
    private final Map<String, App> apps = new ConcurrentHashMap<>();

    /** The thread pool for running apps. */
    private final ExecutorService executorService;

    /** The lock to prevent unsafe access to {@link #apps}. */
    private final ReentrantReadWriteLock lock =
            new ReentrantReadWriteLock();

    /**
     * Constructor.
     *
     * @param agentHome       The agent's home directory.
     * @param executorService The thread pool.
     */
    @Autowired
    public WatchDog(
            @Value("${agent.home}") final String agentHome,
            final ExecutorService executorService) {
        this.agentHome = agentHome;
        this.executorService = executorService;
    }

    /**
     * Starts the provided application, by name.
     *
     * @param manifest The manifest.
     * @param version  The version.
     *
     * @throws Exception on failure to start.
     */
    public void startApp(
            final AppManifest manifest,
            final String version) throws Exception {
        this.lock.writeLock().lock();
        try {
            final App app =
                    this.apps.computeIfAbsent(
                            manifest.alias,
                            s -> new AppImpl(
                                    manifest.app,
                                    FileUtils.toFilePath(
                                            this.agentHome,
                                            manifest,
                                            version,
                                            AppFolder.BIN,
                                            manifest.executable),
                                    this.executorService));
            if (app.isNotRunning()) {
                LOG.info("Starting app {}", manifest.app);
                app.restart();
            } else {
                LOG.debug("{} is already running", manifest.app);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Stops the application with the provided name.
     *
     * @param appName The application name.
     */
    public void stopApp(final String appName) {
        this.lock.writeLock().lock();
        try {
            final App app = this.apps.remove(appName);
            if (app != null) {
                LOG.info("Stopping {}", appName);
                app.stop();
            } else {
                LOG.debug("{} isn't running", appName);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /** Checks the known applications and starts and ones that have stopped. */
    @Scheduled(fixedRateString = "60000")
    private void monitor() {
        LOG.info("Checking if any applications need to be restarted");
        this.lock.readLock().lock();
        try {
            this.apps
                    .values()
                    .stream()
                    .filter(App::isNotRunning)
                    .forEach(app -> {
                        try {
                            LOG.info("Restarting {}", app.getName());
                            app.restart();
                        } catch (final Exception e) {
                            LOG.warn("Failed to restart app", e);
                        }
                    });
        } finally {
            this.lock.readLock().unlock();
        }
    }
}