package io.quarkiverse.solace.base;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.shaded.org.apache.commons.lang3.tuple.Pair;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Ulimit;

public class SolaceContainer extends GenericContainer<SolaceContainer> {

    public static final String INTEGRATION_TEST_QUEUE_NAME = "integration-test-queue";
    public static final String INTEGRATION_TEST_QUEUE_SUBSCRIPTION = "quarkus/integration/test/provisioned/queue/topic";
    public static final String INTEGRATION_TEST_DMQ_NAME = "integration-test-queue-dmq";
    public static final String INTEGRATION_TEST_ERROR_QUEUE_NAME = "integration-test-error-queue";
    public static final String INTEGRATION_TEST_ERROR_QUEUE_SUBSCRIPTION = "quarkus/integration/test/provisioned/queue/error/topic";

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("solace/solace-pubsub-standard");

    private static final String DEFAULT_VPN = "default";

    private static final String DEFAULT_USERNAME = "default";

    private static final String SOLACE_READY_MESSAGE = ".*Running pre-startup checks:.*";

    private static final String SOLACE_ACTIVE_MESSAGE = "Primary Virtual Router is now active";

    private static final String TMP_SCRIPT_LOCATION = "/tmp/script.cli";

    private static final Long SHM_SIZE = (long) Math.pow(1024, 3);

    private String username = "root";

    private String password = "password";

    private String vpn = DEFAULT_VPN;

    private final List<Pair<String, Service>> publishTopicsConfiguration = new ArrayList<>();
    private final List<Pair<String, Service>> subscribeTopicsConfiguration = new ArrayList<>();

    private boolean withClientCert;

    /**
     * Create a new solace container with the specified image name.
     *
     * @param dockerImageName the image name that should be used.
     */
    public SolaceContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    public SolaceContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
        withCreateContainerCmdModifier(cmd -> {
            cmd.getHostConfig().withShmSize(SHM_SIZE).withUlimits(new Ulimit[] { new Ulimit("nofile", 2448L, 6592L) });
        });
        this.waitStrategy = Wait.forLogMessage(SOLACE_READY_MESSAGE, 1).withStartupTimeout(Duration.ofSeconds(60));
        withExposedPorts(8080);
        withEnv("system_scaling_maxconnectioncount", "100");
        withEnv("logging_system_output", "all");
        withEnv("username_admin_globalaccesslevel", "admin");
        withEnv("username_admin_password", "admin");
    }

    @Override
    protected void configure() {
        withCopyToContainer(createConfigurationScript(), TMP_SCRIPT_LOCATION);
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo) {
        if (withClientCert) {
            executeCommand("cp", "/tmp/solace.pem", "/usr/sw/jail/certs/solace.pem");
            executeCommand("cp", "/tmp/rootCA.crt", "/usr/sw/jail/certs/rootCA.crt");
        }
        executeCommand("cp", TMP_SCRIPT_LOCATION, "/usr/sw/jail/cliscripts/script.cli");
        waitOnCommandResult(SOLACE_ACTIVE_MESSAGE, "grep", "-R", SOLACE_ACTIVE_MESSAGE, "/usr/sw/jail/logs/system.log");
        executeCommand("/usr/sw/loads/currentload/bin/cli", "-A", "-es", "script.cli");
    }

    private Transferable createConfigurationScript() {
        StringBuilder scriptBuilder = new StringBuilder();
        updateConfigScript(scriptBuilder, "enable");
        updateConfigScript(scriptBuilder, "configure");

        // create replay log
        updateConfigScript(scriptBuilder, "message-spool message-vpn default");
        updateConfigScript(scriptBuilder, "create replay-log integration-test-replay-log");
        updateConfigScript(scriptBuilder, "max-spool-usage 10");
        updateConfigScript(scriptBuilder, "no shutdown");
        updateConfigScript(scriptBuilder, "exit");
        updateConfigScript(scriptBuilder, "exit");

        // create Error queue, DMQ and a queue. Assign DMQ to queue

        // Error Queue
        updateConfigScript(scriptBuilder, "message-spool message-vpn default");
        updateConfigScript(scriptBuilder, "create queue " + INTEGRATION_TEST_ERROR_QUEUE_NAME);
        updateConfigScript(scriptBuilder, "access-type exclusive");
        updateConfigScript(scriptBuilder, "max-spool-usage 300");
        updateConfigScript(scriptBuilder, "subscription topic " + INTEGRATION_TEST_ERROR_QUEUE_SUBSCRIPTION);
        updateConfigScript(scriptBuilder, "permission all consume");
        updateConfigScript(scriptBuilder, "no shutdown");
        updateConfigScript(scriptBuilder, "exit");
        updateConfigScript(scriptBuilder, "exit");

        // DMQ
        updateConfigScript(scriptBuilder, "message-spool message-vpn default");
        updateConfigScript(scriptBuilder, "create queue " + INTEGRATION_TEST_DMQ_NAME);
        updateConfigScript(scriptBuilder, "access-type exclusive");
        updateConfigScript(scriptBuilder, "max-spool-usage 300");
        updateConfigScript(scriptBuilder, "permission all consume");
        updateConfigScript(scriptBuilder, "no shutdown");
        updateConfigScript(scriptBuilder, "exit");
        updateConfigScript(scriptBuilder, "exit");

        // Queue with DMQ assigned
        updateConfigScript(scriptBuilder, "message-spool message-vpn default");
        updateConfigScript(scriptBuilder, "create queue " + INTEGRATION_TEST_QUEUE_NAME);
        updateConfigScript(scriptBuilder, "access-type exclusive");
        updateConfigScript(scriptBuilder, "subscription topic " + INTEGRATION_TEST_QUEUE_SUBSCRIPTION);
        updateConfigScript(scriptBuilder, "max-spool-usage 300");
        updateConfigScript(scriptBuilder, "permission all consume");
        updateConfigScript(scriptBuilder, "dead-message-queue " + INTEGRATION_TEST_DMQ_NAME);
        updateConfigScript(scriptBuilder, "no shutdown");
        updateConfigScript(scriptBuilder, "exit");
        updateConfigScript(scriptBuilder, "exit");

//        // Integration test user acl
//        updateConfigScript(scriptBuilder,
//                "create acl-profile integration-test-user-acl message-vpn " + vpn + " allow-client-connect");
//        updateConfigScript(scriptBuilder, "exit");
//
//        updateConfigScript(scriptBuilder, "acl-profile integration-test-user-acl message-vpn " + vpn);
//        updateConfigScript(scriptBuilder, "publish-topic exceptions smf list quarkus/integration/test");
//        updateConfigScript(scriptBuilder, "exit");
//
//        // Integration test user
//        updateConfigScript(scriptBuilder, "create client-username " + "int_user" + " message-vpn " + vpn);
//        updateConfigScript(scriptBuilder, "password " + "int_pass");
//        updateConfigScript(scriptBuilder, "acl-profile integration-test-user-acl");
//        updateConfigScript(scriptBuilder, "client-profile default");
//        updateConfigScript(scriptBuilder, "no shutdown");
//        updateConfigScript(scriptBuilder, "exit");
//
//        updateConfigScript(scriptBuilder, "client-profile default");
//        updateConfigScript(scriptBuilder, "allow-guaranteed-message-receive");
//        updateConfigScript(scriptBuilder, "allow-guaranteed-message-send");
//        updateConfigScript(scriptBuilder, "exit");

        // Create VPN if not default
        if (!vpn.equals(DEFAULT_VPN)) {
            updateConfigScript(scriptBuilder, "create message-vpn " + vpn);
            updateConfigScript(scriptBuilder, "no shutdown");
            updateConfigScript(scriptBuilder, "exit");
        }

        // Configure username and password
        if (username.equals(DEFAULT_USERNAME)) {
            throw new RuntimeException("Cannot override password for default client");
        }
        updateConfigScript(scriptBuilder, "create client-username " + username + " message-vpn " + vpn);
        updateConfigScript(scriptBuilder, "password " + password);
        updateConfigScript(scriptBuilder, "acl-profile default");
        updateConfigScript(scriptBuilder, "client-profile default");
        updateConfigScript(scriptBuilder, "no shutdown");
        updateConfigScript(scriptBuilder, "exit");

        if (withClientCert) {
            // Client certificate authority configuration
            updateConfigScript(scriptBuilder, "authentication");
            updateConfigScript(scriptBuilder, "create client-certificate-authority RootCA");
            updateConfigScript(scriptBuilder, "certificate file rootCA.crt");
            updateConfigScript(scriptBuilder, "show client-certificate-authority ca-name *");
            updateConfigScript(scriptBuilder, "end");

            // Server certificates configuration
            updateConfigScript(scriptBuilder, "configure");
            updateConfigScript(scriptBuilder, "ssl");
            updateConfigScript(scriptBuilder, "server-certificate solace.pem");
            updateConfigScript(scriptBuilder, "cipher-suite msg-backbone name AES128-SHA");
            updateConfigScript(scriptBuilder, "exit");

            updateConfigScript(scriptBuilder, "message-vpn " + vpn);
            // Enable client certificate authentication
            updateConfigScript(scriptBuilder, "authentication client-certificate");
            updateConfigScript(scriptBuilder, "allow-api-provided-username");
            updateConfigScript(scriptBuilder, "no shutdown");
            updateConfigScript(scriptBuilder, "end");
        } else {
            // Configure VPN Basic authentication
            updateConfigScript(scriptBuilder, "message-vpn " + vpn);
            updateConfigScript(scriptBuilder, "authentication basic auth-type internal");
            updateConfigScript(scriptBuilder, "no shutdown");
            updateConfigScript(scriptBuilder, "end");
        }

        if (!publishTopicsConfiguration.isEmpty() || !subscribeTopicsConfiguration.isEmpty()) {
            // Enable services
            updateConfigScript(scriptBuilder, "configure");
            // Configure default ACL
            updateConfigScript(scriptBuilder, "acl-profile default message-vpn " + vpn);
            // Configure default action to disallow
            if(!subscribeTopicsConfiguration.isEmpty()) {
                updateConfigScript(scriptBuilder, "subscribe-topic default-action disallow");
            }
            if(!publishTopicsConfiguration.isEmpty()) {
                updateConfigScript(scriptBuilder, "publish-topic default-action disallow");
            }
            updateConfigScript(scriptBuilder, "exit");

            updateConfigScript(scriptBuilder, "message-vpn " + vpn);
            updateConfigScript(scriptBuilder, "service");
            for (Pair<String, Service> topicConfig : publishTopicsConfiguration) {
                Service service = topicConfig.getValue();
                String topicName = topicConfig.getKey();
                updateConfigScript(scriptBuilder, service.getName());
                if (service.isSupportSSL()) {
                    if (withClientCert) {
                        updateConfigScript(scriptBuilder, "ssl");
                    } else {
                        updateConfigScript(scriptBuilder, "plain-text");
                    }
                }
                updateConfigScript(scriptBuilder, "no shutdown");
                updateConfigScript(scriptBuilder, "end");
                // Add publish/subscribe topic exceptions
                updateConfigScript(scriptBuilder, "configure");
                updateConfigScript(scriptBuilder, "acl-profile default message-vpn " + vpn);
                updateConfigScript(
                        scriptBuilder,
                        String.format("publish-topic exceptions %s list %s", service.getName(), topicName));
                updateConfigScript(scriptBuilder, "end");
            }

            updateConfigScript(scriptBuilder, "configure");
            updateConfigScript(scriptBuilder, "message-vpn " + vpn);
            updateConfigScript(scriptBuilder, "service");
            for (Pair<String, Service> topicConfig : subscribeTopicsConfiguration) {
                Service service = topicConfig.getValue();
                String topicName = topicConfig.getKey();
                updateConfigScript(scriptBuilder, service.getName());
                if (service.isSupportSSL()) {
                    if (withClientCert) {
                        updateConfigScript(scriptBuilder, "ssl");
                    } else {
                        updateConfigScript(scriptBuilder, "plain-text");
                    }
                }
                updateConfigScript(scriptBuilder, "no shutdown");
                updateConfigScript(scriptBuilder, "end");
                // Add publish/subscribe topic exceptions
                updateConfigScript(scriptBuilder, "configure");
                updateConfigScript(scriptBuilder, "acl-profile default message-vpn " + vpn);
                updateConfigScript(
                        scriptBuilder,
                        String.format("subscribe-topic exceptions %s list %s", service.getName(), topicName));
                updateConfigScript(scriptBuilder, "end");
            }
        }
        return Transferable.of(scriptBuilder.toString());
    }

    private void executeCommand(String... command) {
        try {
            ExecResult execResult = execInContainer(command);
            if (execResult.getExitCode() != 0) {
                logCommandError(execResult.getStderr(), command);
            }
        } catch (IOException | InterruptedException e) {
            logCommandError(e.getMessage(), command);
        }
    }

    private void updateConfigScript(StringBuilder scriptBuilder, String command) {
        scriptBuilder.append(command).append("\n");
    }

    private void waitOnCommandResult(String waitingFor, String... command) {
        Awaitility
                .await()
                .pollInterval(Duration.ofMillis(500))
                .timeout(Duration.ofSeconds(30))
                .until(() -> {
                    try {
                        return execInContainer(command).getStdout().contains(waitingFor);
                    } catch (IOException | InterruptedException e) {
                        logCommandError(e.getMessage(), command);
                        return true;
                    }
                });
    }

    private void logCommandError(String error, String... command) {
        logger().error("Could not execute command {}: {}", command, error);
    }

    /**
     * Sets the client credentials
     *
     * @param username Client username
     * @param password Client password
     * @return This container.
     */
    public SolaceContainer withCredentials(final String username, final String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    /**
     * Adds the topic configuration
     *
     * @param topic Name of the topic
     * @param service Service to be supported on provided topic
     * @return This container.
     */
//    public SolaceContainer withTopic(String topic, Service service) {
//        topicsConfiguration.add(Pair.of(topic, service));
//        addExposedPort(service.getPort());
//        return this;
//    }

    /**
     * Adds the publish topic exceptions configuration
     *
     * @param topic Name of the topic
     * @param service Service to be supported on provided topic
     * @return This container.
     */
    public SolaceContainer withPublishTopic(String topic, Service service) {
        publishTopicsConfiguration.add(Pair.of(topic, service));
        addExposedPort(service.getPort());
        return this;
    }

    /**
     * Adds the subscribe topic exceptions configuration
     *
     * @param topic Name of the topic
     * @param service Service to be supported on provided topic
     * @return This container.
     */
    public SolaceContainer withSubscribeTopic(String topic, Service service) {
        subscribeTopicsConfiguration.add(Pair.of(topic, service));
        addExposedPort(service.getPort());
        return this;
    }

    /**
     * Sets the VPN name
     *
     * @param vpn VPN name
     * @return This container.
     */
    public SolaceContainer withVpn(String vpn) {
        this.vpn = vpn;
        return this;
    }

    /**
     * Sets the solace server ceritificates
     *
     * @param certFile Server certificate
     * @param caFile Certified Authority ceritificate
     * @return This container.
     */
    public SolaceContainer withClientCert(final MountableFile certFile, final MountableFile caFile) {
        this.withClientCert = true;
        return withCopyFileToContainer(certFile, "/tmp/solace.pem").withCopyFileToContainer(caFile, "/tmp/rootCA.crt");
    }

    /**
     * Configured VPN
     *
     * @return the configured VPN that should be used for connections
     */
    public String getVpn() {
        return this.vpn;
    }

    /**
     * Host address for provided service
     *
     * @param service - service for which host needs to be retrieved
     * @return host address exposed from the container
     */
    public String getOrigin(Service service) {
        return String.format("%s://%s:%s", service.getProtocol(), getHost(), getMappedPort(service.getPort()));
    }

    /**
     * Configured username
     *
     * @return the standard username that should be used for connections
     */
    public String getUsername() {
        return this.username;
    }

    /**
     * Configured password
     *
     * @return the standard password that should be used for connections
     */
    public String getPassword() {
        return this.password;
    }

    public enum Service {
        AMQP("amqp", 5672, "amqp", false),
        MQTT("mqtt", 1883, "tcp", false),
        REST("rest", 9000, "http", false),
        SMF("smf", 55555, "tcp", true),
        SMF_SSL("smf", 55443, "tcps", true);

        private final String name;
        private final Integer port;
        private final String protocol;
        private final boolean supportSSL;

        Service(String name, Integer port, String protocol, boolean supportSSL) {
            this.name = name;
            this.port = port;
            this.protocol = protocol;
            this.supportSSL = supportSSL;
        }

        /**
         * @return Port assigned for the service
         */
        public Integer getPort() {
            return this.port;
        }

        /**
         * @return Protocol of the service
         */
        public String getProtocol() {
            return this.protocol;
        }

        /**
         * @return Name of the service
         */
        public String getName() {
            return this.name;
        }

        /**
         * @return Is SSL for this service supported ?
         */
        public boolean isSupportSSL() {
            return this.supportSSL;
        }
    }
}
