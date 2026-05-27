package mn.foreman.windowsagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Unit tests for {@link AppFolder}. */
class AppFolderTest {

    /** Verifies the {@link AppFolder#BIN} folder name. */
    @Test
    void binFolder() {
        assertEquals("bin", AppFolder.BIN.getFolder());
    }

    /** Verifies the {@link AppFolder#CONF} folder name. */
    @Test
    void confFolder() {
        assertEquals("conf", AppFolder.CONF.getFolder());
    }

    /** Verifies that there are exactly two folders defined. */
    @Test
    void valuesAreComplete() {
        final AppFolder[] values = AppFolder.values();
        assertEquals(2, values.length);
        assertNotNull(AppFolder.valueOf("BIN"));
        assertNotNull(AppFolder.valueOf("CONF"));
    }
}
