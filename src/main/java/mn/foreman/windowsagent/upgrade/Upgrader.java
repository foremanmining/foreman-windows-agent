package mn.foreman.windowsagent.upgrade;

import mn.foreman.api.ForemanApi;
import mn.foreman.windowsagent.VersionFactory;
import mn.foreman.windowsagent.process.WatchDog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Periodically checks and runs {@link UpgradeTask upgrade tasks}. */
@Component
public class Upgrader {

    /** The API key. */
    private final String apiKey;

    /** The apps manifest. */
    private final String appsManifest;

    /** The client ID. */
    private final String clientId;

    /** The colo dist. */
    private final String coloDist;

    /** Whether or not colo is enabled. */
    private final boolean coloEnabled;

    /** The color version factory. */
    private final VersionFactory coloVersionFactory;

    /** The API handle. */
    private final ForemanApi foremanApi;

    /** The private dist. */
    private final String privateDist;

    /** The private version factory. */
    private final VersionFactory privateVersionFactory;

    /** The rest template. */
    private final RestTemplate restTemplate;

    /** The scale. */
    private final int scale;

    /** The watchdog. */
    private final WatchDog watchDog;

    /**
     * Constructor.
     *
     * @param appsManifest          The apps manifest.
     * @param clientId              The client ID.
     * @param apiKey                The API key.
     * @param privateDist           The private dist folder.
     * @param scale                 The scale for private dists.
     * @param coloEnabled           Whether or not colo is enabled.
     * @param coloDist              The color dist folder.
     * @param privateVersionFactory The version factory for obtaining private
     *                              installation application versions.
     * @param coloVersionFactory    The version factory for obtaining colo
     *                              installation application versions.
     * @param foremanApi            The API handle.
     * @param watchDog              The watchdog.
     * @param restTemplate          The template for performing rest
     *                              operations.
     */
    @Autowired
    public Upgrader(
            @Value("${apps.manifest}") final String appsManifest,
            @Value("${client.id}") final String clientId,
            @Value("${client.apiKey}") final String apiKey,
            @Value("${agent.dist}") final String privateDist,
            @Value("${agent.scale}") final int scale,
            @Value("${agent.colo.enabled}") final boolean coloEnabled,
            @Value("${agent.colo.dist}") final String coloDist,
            @Qualifier("privateVersionFactory") final VersionFactory privateVersionFactory,
            @Qualifier("coloVersionFactory") final VersionFactory coloVersionFactory,
            final ForemanApi foremanApi,
            final WatchDog watchDog,
            final RestTemplate restTemplate) {
        this.appsManifest = appsManifest;
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.privateDist = privateDist;
        this.scale = scale;
        this.coloEnabled = coloEnabled;
        this.coloDist = coloDist;
        this.privateVersionFactory = privateVersionFactory;
        this.coloVersionFactory = coloVersionFactory;
        this.foremanApi = foremanApi;
        this.watchDog = watchDog;
        this.restTemplate = restTemplate;
    }

    /** Scheduled task to continuously check for upgrades. */
    @Scheduled(fixedRateString = "${upgrade.check.frequency}")
    public void check() {
        final List<UpgradeTask> upgradeTasks =
                new LinkedList<>();
        upgradeTasks.add(
                new UpgradeTask(
                        this.appsManifest,
                        this.privateDist,
                        IntStream
                                .range(0, this.scale)
                                .boxed()
                                .collect(Collectors.toList()),
                        s -> this.clientId,
                        this.apiKey,
                        this.privateVersionFactory,
                        this.watchDog,
                        this.restTemplate));
        if (this.coloEnabled) {
            final List<Integer> clientIds = new LinkedList<>();
            this.foremanApi
                    .groups()
                    .groups()
                    .ifPresent(groupInfo -> {
                        if (groupInfo.subClients != null) {
                            groupInfo.subClients
                                    .stream()
                                    .mapToInt(subClient -> subClient.id)
                                    .forEach(clientIds::add);
                        }
                    });
            if (!clientIds.isEmpty()) {
                upgradeTasks.add(
                        new UpgradeTask(
                                this.appsManifest,
                                this.coloDist,
                                clientIds,
                                s -> s,
                                this.apiKey,
                                this.coloVersionFactory,
                                this.watchDog,
                                this.restTemplate));
            }
        }
        upgradeTasks.forEach(UpgradeTask::check);
    }
}
