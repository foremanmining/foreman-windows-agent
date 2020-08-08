package mn.foreman.windowsagent;

/** An application folder. */
public enum AppFolder {

    /** bin folder. */
    BIN("bin"),

    /** conf folder. */
    CONF("conf");

    /** The folder. */
    private final String folder;

    /**
     * Constructor.
     *
     * @param folder The folder.
     */
    AppFolder(final String folder) {
        this.folder = folder;
    }

    /**
     * Returns the folder.
     *
     * @return The folder.
     */
    public String getFolder() {
        return this.folder;
    }
}
