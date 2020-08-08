package mn.foreman.windowsagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Application entry point. */
@SpringBootApplication
@EnableScheduling
public class Application {

    /**
     * Application entry point.
     *
     * @param args The command line arguments.
     */
    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}