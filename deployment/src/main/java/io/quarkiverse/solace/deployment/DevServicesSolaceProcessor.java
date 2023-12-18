package io.quarkiverse.solace.deployment;

import static io.quarkus.runtime.LaunchMode.DEVELOPMENT;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Ulimit;

import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.configuration.ConfigUtils;

@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = { GlobalDevServicesConfig.Enabled.class })
public class DevServicesSolaceProcessor {
    private static final Logger log = Logger.getLogger(DevServicesSolaceProcessor.class);
    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";
    private static final String SOLACE_READY_MESSAGE = ".*Primary Virtual Router is now active.*";

    /**
     * Label to add to shared Dev Service for Solace running in containers.
     * This allows other applications to discover the running service and use it instead of starting a new instance.
     */
    private static final String DEV_SERVICE_LABEL = "quarkus-dev-service-solace";

    private static final ContainerLocator solaceContainerLocator = new ContainerLocator(DEV_SERVICE_LABEL, 55555);
    private static volatile RunningDevService running;
    private static volatile SolaceDevServiceConfig cfg;
    private static volatile boolean first = true;

    @BuildStep
    public DevServicesResultBuildItem startSolaceContainer(LaunchModeBuildItem launchMode,
            DockerStatusBuildItem dockerStatusBuildItem,
            List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem,
            SolaceBuildTimeConfig config,
            Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
            CuratedApplicationShutdownBuildItem closeBuildItem,
            LoggingSetupBuildItem loggingSetupBuildItem,
            GlobalDevServicesConfig devServicesConfig) {

        SolaceDevServiceConfig configuration = getConfiguration(config);

        if (running != null) {
            boolean shouldShutdownTheBroker = !configuration.equals(cfg);
            if (!shouldShutdownTheBroker) {
                return running.toBuildItem();
            }
            try {
                running.close();
            } catch (Throwable e) {
                log.error("Failed to stop solace container", e);
            }
            running = null;
            cfg = null;
        }

        StartupLogCompressor compressor = new StartupLogCompressor(
                (launchMode.isTest() ? "(test) " : "") + "Solace Dev Services Starting:", consoleInstalledBuildItem,
                loggingSetupBuildItem);
        try {
            RunningDevService devService = startContainer(dockerStatusBuildItem,
                    configuration,
                    launchMode.getLaunchMode(),
                    !devServicesSharedNetworkBuildItem.isEmpty(), devServicesConfig.timeout);

            if (devService != null) {
                String configKey = "quarkus.solace.host";
                log.infof("The solace broker is ready to accept connections on %s",
                        devService.getConfig().get(configKey));
                compressor.close();
                running = devService;
            } else {
                compressor.closeAndDumpCaptured();
                return null;
            }
        } catch (Throwable t) {
            compressor.closeAndDumpCaptured();
            throw new RuntimeException(t);
        }

        if (first) {
            first = false;
            Runnable closeTask = () -> {
                if (running != null) {
                    try {
                        running.close();
                    } catch (Throwable t) {
                        log.error("Failed to stop database", t);
                    }
                }
                first = true;
                running = null;
                cfg = null;
            };
            closeBuildItem.addCloseTask(closeTask, true);
        }
        cfg = configuration;

        return running.toBuildItem();

    }

    private SolaceDevServiceConfig getConfiguration(SolaceBuildTimeConfig config) {
        SolaceBuildTimeConfig.DevServiceConfiguration cfg = config.defaultDevService();
        return new SolaceDevServiceConfig(cfg);
    }

    private RunningDevService startContainer(DockerStatusBuildItem dockerStatusBuildItem,
            SolaceDevServiceConfig devServicesConfig, LaunchMode launchMode,
            boolean useSharedNetwork, Optional<Duration> timeout) {
        if (!devServicesConfig.enabled) {
            // explicitly disabled
            log.debug("Not starting devservices for solace as it has been disabled in the config");
            return null;
        }

        boolean needToStart = !ConfigUtils.isPropertyPresent("quarkus.solace.host");
        if (!needToStart) {
            log.debug("Not starting devservices for solace as `quarkus.solace.host` have been provided");
            return null;
        }

        if (!dockerStatusBuildItem.isDockerAvailable()) {
            log.warn(
                    "Please configure `quarkus.solace.host` to point to a running Solace instance or get a working docker instance");
            return null;
        }

        DockerImageName dockerImageName = DockerImageName.parse(devServicesConfig.imageName)
                .asCompatibleSubstituteFor(SOLACE_IMAGE);

        Supplier<RunningDevService> supplier = () -> {
            QuarkusSolaceContainer container = new QuarkusSolaceContainer(dockerImageName,
                    launchMode == DEVELOPMENT ? devServicesConfig.serviceName : null, useSharedNetwork);
            timeout.ifPresent(container::withStartupTimeout);
            container.withEnv(devServicesConfig.containerEnv);
            container.start();

            String host = container.getHost() + ":" + container.getPort();
            Map<String, String> config = Map.of("quarkus.solace.host", host,
                    "quarkus.solace.vpn", "default",
                    "quarkus.solace.authentication.basic.username", "admin",
                    "quarkus.solace.authentication.basic.password", "admin");
            return new RunningDevService("solace", container.getContainerId(),
                    container::close, config);
        };

        return solaceContainerLocator.locateContainer(devServicesConfig.serviceName, devServicesConfig.shared, launchMode)
                .map(containerAddress -> {
                    String host = containerAddress.getUrl();
                    Map<String, String> config = Map.of("quarkus.solace.host", host,
                            "quarkus.solace.vpn", "default",
                            "quarkus.solace.authentication.basic.username", "admin",
                            "quarkus.solace.authentication.basic.password", "admin");
                    return new RunningDevService("solace", containerAddress.getId(),
                            null, config);
                })
                .orElseGet(supplier);
    }

    private static class QuarkusSolaceContainer extends GenericContainer<QuarkusSolaceContainer> {
        private final boolean useSharedNetwork;

        private String hostName = null;

        public QuarkusSolaceContainer(DockerImageName dockerImageName, String serviceName,
                boolean useSharedNetwork) {
            super(dockerImageName);

            withEnv("system_scaling_maxconnectioncount", "100");
            withEnv("logging_system_output", "all");

            addExposedPort(8008); // Web Transport
            addExposedPort(1443);// Web transport over TLS
            addExposedPort(1943); // SEMP over TLS
            addExposedPort(1883); // MQTT Default VPN
            addExposedPort(5671); // AMQP Default VPN over TLS
            addExposedPort(5672); // AMQP Default VPN
            addExposedPort(8000);// MQTT Default VPN over WebSockets
            addExposedPort(8443); // MQTT Default VPN over WebSockets / TLS
            addExposedPort(8883); // MQTT Default VPN over TLS
            addExposedPort(8080); // SEMP / PubSub+ Manager
            addExposedPort(9000); // REST Default VPN
            addExposedPort(9443); // REST Default VPN over TLS
            addExposedPort(55555); // SMF
            addExposedPort(55003); // SMF Compressed
            addExposedPort(55443); // SMF over TLS
            addExposedPort(2222); // SSH connection to CLI

            withCreateContainerCmdModifier(cmd -> {
                cmd.getHostConfig().withShmSize((long) Math.pow(1024, 3))
                        .withUlimits(new Ulimit[] {
                                new Ulimit("core", -1, -1),
                                new Ulimit("memlock", -1, -1),
                                new Ulimit("nofile", 2448L, 42192L),
                        })
                        .withCpusetCpus("0-1")
                        .withMemorySwap(-1L)
                        .withMemoryReservation(0L);
            });

            withEnv("username_admin_globalaccesslevel", "admin");
            withEnv("username_admin_password", "admin");

            this.useSharedNetwork = useSharedNetwork;

            if (serviceName != null) {
                withLabel(DEV_SERVICE_LABEL, serviceName);
            }

            this.hostName = "solace";
            setWaitStrategy(Wait.forLogMessage(SOLACE_READY_MESSAGE, 1).withStartupTimeout(Duration.ofSeconds(60)));
        }

        @Override
        protected void configure() {
            super.configure();

            if (useSharedNetwork) {
                hostName = ConfigureUtil.configureSharedNetwork(this, "solace");
                return;
            }
        }

        public int getPort() {
            if (useSharedNetwork) {
                return 55555;
            }

            return super.getMappedPort(55555);
        }

        @Override
        public String getHost() {
            return useSharedNetwork ? hostName : super.getHost();
        }
    }

    private static class SolaceDevServiceConfig {

        final boolean enabled;
        final String serviceName;
        final String imageName;
        final boolean shared;
        final Map<String, String> containerEnv;

        public SolaceDevServiceConfig(SolaceBuildTimeConfig.DevServiceConfiguration cfg) {
            enabled = cfg.devservices().enabled();
            serviceName = cfg.devservices().serviceName();
            imageName = cfg.devservices().imageName().orElse(SOLACE_IMAGE);
            shared = cfg.devservices().shared();
            containerEnv = cfg.devservices().containerEnv();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            SolaceDevServiceConfig that = (SolaceDevServiceConfig) o;
            return enabled == that.enabled && shared == that.shared && Objects.equals(serviceName, that.serviceName)
                    && Objects.equals(imageName, that.imageName) && Objects.equals(containerEnv, that.containerEnv);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, serviceName, imageName, shared, containerEnv);
        }
    }
}
