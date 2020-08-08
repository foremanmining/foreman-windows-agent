package mn.foreman.windowsagent.process;

/** An {@link App} represents an application that is managed by the agent. */
public interface App {

    /**
     * Returns the application's name.
     *
     * @return The name.
     */
    String getName();

    /**
     * Checks to see if the app is running.
     *
     * @return Whether or not the app is running.
     */
    boolean isNotRunning();

    /**
     * Restarts the application.
     *
     * @throws Exception on failure to start.
     */
    void restart() throws Exception;

    /**
     * Starts the application.
     *
     * @throws Exception on failure to start.
     */
    void start() throws Exception;

    /** Stops the application. */
    void stop();
}
