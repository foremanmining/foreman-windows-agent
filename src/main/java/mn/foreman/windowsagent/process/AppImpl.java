package mn.foreman.windowsagent.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** An {@link App} implementation. */
public class AppImpl
        implements App {

    /** Java location. */
    private static final String JAVA_HOME =
            System.getenv("JAVA_HOME");

    /** The logger for this class. */
    private static final Logger LOG =
            LoggerFactory.getLogger(AppImpl.class);

    /** The thread pool for {@link Drain}. */
    private final ExecutorService executor;

    /** The application name. */
    private final String name;

    /** The path (working dir). */
    private final Path path;

    /** Whether or not the app is running. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** The process that was forked. */
    private Process process;

    /** The drain for this app. */
    private Future<?> task;

    /**
     * Constructor.
     *
     * @param name     The name.
     * @param path     The path.
     * @param executor The thread pool to run in.
     */
    AppImpl(
            final String name,
            final Path path,
            final ExecutorService executor) {
        this.name = name;
        this.path = path;
        this.executor = executor;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public boolean isNotRunning() {
        return !this.running.get();
    }

    @Override
    public void restart() throws Exception {
        stop();
        start();
    }

    @Override
    public void start() throws Exception {
        if (!this.running.get()) {
            final ProcessBuilder processBuilder =
                    new ProcessBuilder(
                            this.path.toString());
            final Map<String, String> environment = processBuilder.environment();
            environment.put("JAVA_HOME", JAVA_HOME);

            this.process = processBuilder.start();
            this.running.set(true);
            this.task = this.executor.submit(new Drain(this.process));
        }
    }

    @Override
    public void stop() {
        if (this.running.compareAndSet(true, false)) {
            this.task.cancel(true);
            this.process
                    .descendants()
                    .forEach(ProcessHandle::destroy);
            this.process.destroy();
        }
    }

    /**
     * A {@link Drain} provides a {@link Runnable} implementation that reads the
     * output from the provided {@link Process}.
     */
    private class Drain
            implements Runnable {

        /** The process to monitor. */
        private final Process process;

        /**
         * Constructor.
         *
         * @param process The process to monitor.
         */
        Drain(final Process process) {
            this.process = process;
        }

        @Override
        public void run() {
            try (final InputStreamReader inputStreamReader =
                         new InputStreamReader(this.process.getInputStream());
                 final BufferedReader bufferedReader =
                         new BufferedReader(inputStreamReader)) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    LOG.info("[{}] {}", AppImpl.this.name, line);
                }
            } catch (final Exception e) {
                LOG.warn("Exception occurred", e);
            }
            AppImpl.this.running.set(false);
        }
    }
}
