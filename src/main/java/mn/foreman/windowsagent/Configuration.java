package mn.foreman.windowsagent;

import mn.foreman.api.ForemanApi;
import mn.foreman.api.ForemanApiImpl;
import mn.foreman.api.JdkWebUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A {@link Configuration} provides a configuration for the windows agent. */
@org.springframework.context.annotation.Configuration
public class Configuration {

    /** The base URL. */
    private static final String FOREMAN_BASE_URL;

    static {
        FOREMAN_BASE_URL =
                System.getProperty(
                        "FOREMAN_BASE_URL",
                        "https://api.foreman.mn");
    }

    /**
     * Returns the {@link VersionFactory} for obtaining versions for
     * colo-related installations.
     *
     * @param agentDist The dist folder.
     *
     * @return The factory.
     */
    @Bean
    public VersionFactory coloVersionFactory(
            @Value("${agent.colo.dist}") final String agentDist) {
        return new VersionFactoryImpl(agentDist);
    }

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
     * Creates a new {@link ForemanApi}.
     *
     * @param clientId The client ID.
     * @param apiKey   The API key.
     *
     * @return The new {@link ForemanApi}.
     */
    @Bean
    public ForemanApi foremanApi(
            @Value("${client.id}") final String clientId,
            @Value("${client.apiKey}") final String apiKey) {
        return new ForemanApiImpl(
                clientId,
                null,
                new ObjectMapper(),
                new JdkWebUtil(
                        FOREMAN_BASE_URL,
                        apiKey));
    }

    /**
     * Returns the {@link VersionFactory} for obtaining versions for private
     * installations.
     *
     * @param agentDist The dist folder.
     *
     * @return The factory.
     */
    @Bean
    public VersionFactory privateVersionFactory(
            @Value("${agent.dist}") final String agentDist) {
        return new VersionFactoryImpl(agentDist);
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
}
