package mn.foreman.windowsagent.process;

import mn.foreman.windowsagent.AppFolder;
import mn.foreman.windowsagent.FileUtils;
import mn.foreman.windowsagent.foreman.AppManifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
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

    /** The applications currently being monitored. */
    private final Map<String, Map<String, App>> apps =
            new ConcurrentHashMap<>();

    /** The thread pool for running apps. */
    private final ExecutorService executorService;

    /** The lock to prevent unsafe access to {@link #apps}. */
    private final ReentrantReadWriteLock lock =
            new ReentrantReadWriteLock();

    /**
     * Constructor.
     *
     * @param executorService The thread pool.
     */
    @Autowired
    public WatchDog(final ExecutorService executorService) {
        this.executorService = executorService;
    }

    /**
     * Starts the provided application, by name.
     *
     * @param dist     The dist.
     * @param manifest The manifest.
     * @param version  The version.
     *
     * @throws Exception on failure to start.
     */
    public void startApp(
            final String dist,
            final AppManifest manifest,
            final String version) throws Exception {
        this.lock.writeLock().lock();
        try {
            final Map<String, App> distMap =
                    this.apps.computeIfAbsent(
                            dist,
                            s -> new ConcurrentHashMap<>());

            final App app =
                    distMap.computeIfAbsent(
                            manifest.alias,
                            s -> new AppImpl(
                                    manifest.app,
                                    FileUtils.toFilePath(
                                            dist,
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
     * @param dist    The dist.
     * @param appName The application name.
     */
    public void stopApp(
            final String dist,
            final String appName) {
        this.lock.writeLock().lock();
        try {
            final Map<String, App> distMap = this.apps.get(dist);
            if (distMap != null && distMap.containsKey(dist)) {
                final App app = distMap.remove(appName);
                if (app != null) {
                    LOG.info("Stopping {}", appName);
                    app.stop();
                } else {
                    LOG.debug("{} isn't running", appName);
                }
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
                    .map(Map::values)
                    .flatMap(Collection::stream)
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