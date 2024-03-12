package com.solace.quarkus.messaging.base;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

public class KeyCloakContainer extends GenericContainer<KeyCloakContainer> {

    public KeyCloakContainer() {
        super("quay.io/keycloak/keycloak:20.0.0");
        addFixedExposedPort(7777, Service.HTTP.getPort());
        addFixedExposedPort(7778, Service.HTTPS.getPort());
        withEnv("KEYCLOAK_ADMIN", "admin");
        withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin");
        withEnv("KEYCLOAK_FRONTEND_URL", "https://localhost:7778");
        withEnv("KC_HOSTNAME_URL", "https://localhost:7778");
        withEnv("KC_HTTPS_CERTIFICATE_FILE", "/opt/keycloak/conf/server.crt");
        withEnv("KC_HTTPS_CERTIFICATE_KEY_FILE", "/opt/keycloak/conf/server.key");
        waitingFor(Wait.forLogMessage(".*Listening.*", 1));
        withNetwork(Network.SHARED);
        withNetworkAliases("keycloak");
        withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/realms/solace-realm.json"),
                "/opt/keycloak/data/import/solace-realm.json");
        withCopyFileToContainer(MountableFile.forClasspathResource("keycloak.crt"), "/opt/keycloak/conf/server.crt");
        withCopyFileToContainer(MountableFile.forClasspathResource("keycloak.key"), "/opt/keycloak/conf/server.key");
        withCommand("start-dev", "--import-realm");
    }

    public void createHostsFile() {
        try (FileWriter fileWriter = new FileWriter("target/hosts")) {
            String dockerHost = this.getHost();
            if ("localhost".equals(dockerHost)) {
                fileWriter.write("127.0.0.1 keycloak");
            } else {
                fileWriter.write(dockerHost + " keycloak");
            }
            fileWriter.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    public enum Service {
        HTTP("http", 8080, "http", false),
        HTTPS("https", 8443, "https", true);

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
