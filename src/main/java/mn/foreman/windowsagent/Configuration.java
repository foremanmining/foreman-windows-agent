package mn.foreman.windowsagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A {@link Configuration} provides a configuration for the windows agent. */
@org.springframework.context.annotation.Configuration
public class Configuration {

    /** The logger for this class. */
    private static final Logger LOG =
            LoggerFactory.getLogger(Configuration.class);

    /**
     * Creates a thread pool for running apps.
     *
     * @return The thread pool.
     */
    @Bean
    public ExecutorService executorService() {
        return Executors.newCachedThreadPool();
    }

    /**
     * Creates the {@link RestTemplate}.
     *
     * @return The {@link RestTemplate}.
     */
    @Bean
    public RestTemplate restTemplate() {
        final RestTemplate restTemplate = new RestTemplate();

        final List<HttpMessageConverter<?>> messageConverters =
                new ArrayList<>(restTemplate.getMessageConverters());

        final MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter();
        converter.setSupportedMediaTypes(
                Collections.singletonList(
                        MediaType.ALL));
        messageConverters.add(converter);

        restTemplate.setMessageConverters(messageConverters);

        return restTemplate;
    }

    /**
     * Creates a {@link Map} containing all of the installed application
     * versions.
     *
     * @param agentDist The dist directory.
     *
     * @return The versions.
     */
    @Bean
    public Map<String, String> versions(
            @Value("${agent.dist}") final String agentDist) {
        final Map<String, String> versions = new ConcurrentHashMap<>();

        FileUtils.forFileIn(
                agentDist,
                File::isDirectory,
                file -> {
                    final String fileName = file.getName();
                    if (fileName.contains("foreman")) {
                        final String[] regions = fileName.split("-");
                        if (regions.length == 3) {
                            versions.put(
                                    regions[0] + "-" + regions[1],
                                    regions[2]);
                        }
                    }
                });

        LOG.info("Currently installed versions: {}", versions);

        return versions;
    }
}
