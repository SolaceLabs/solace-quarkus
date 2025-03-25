package com.solace.quarkus.messaging;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.io.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;

import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.testcontainers.utility.MountableFile;

import com.solace.messaging.MessagingService;
import com.solace.messaging.config.SolaceProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.resources.Topic;
import com.solace.quarkus.messaging.base.KeyCloakContainer;
import com.solace.quarkus.messaging.base.SolaceContainer;
import com.solace.quarkus.messaging.base.UnsatisfiedInstance;
import com.solace.quarkus.messaging.incoming.SolaceIncomingChannel;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;
import io.vertx.mutiny.core.Vertx;

public class SolaceOAuthTest {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";

    private static SolaceContainer createSolaceContainer() {
        return new SolaceContainer(SOLACE_IMAGE);
    }

    private static KeyCloakContainer keyCloakContainer;
    private static SolaceContainer solaceContainer;

    @BeforeAll
    static void startContainers() {
        keyCloakContainer = new KeyCloakContainer();
        keyCloakContainer.start();
        keyCloakContainer.createHostsFile();
        await().until(() -> keyCloakContainer.isRunning());

        solaceContainer = createSolaceContainer();
        solaceContainer.withCredentials("user", "pass")
                .withClientCert(MountableFile.forClasspathResource("solace.pem"),
                        MountableFile.forClasspathResource("keycloak.crt"), false)
                .withOAuth()
                .withExposedPorts(SolaceContainer.Service.SMF.getPort(), SolaceContainer.Service.SMF_SSL.getPort(), 1943, 8080)
                .withPublishTopic("quarkus/integration/test/replay/messages", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/default/>", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/provisioned/>", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/dynamic/>", SolaceContainer.Service.SMF);

        solaceContainer.start();
        await().until(() -> solaceContainer.isRunning());
    }

    private static KeyStore createKeyStore(byte[] ca, byte[] serviceCa) {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            if (ca != null) {
                keyStore.setCertificateEntry("keycloak",
                        cf.generateCertificate(new ByteArrayInputStream(ca)));
            }
            if (serviceCa != null) {
                keyStore.setCertificateEntry("service-ca",
                        cf.generateCertificate(new ByteArrayInputStream(serviceCa)));
            }
            return keyStore;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getAccessToken() throws IOException {
        ClassLoader classLoader = SolaceOAuthTest.class.getClassLoader();
        InputStream is = new FileInputStream(classLoader.getResource("keycloak.crt").getFile());
        KeyStore trustStore = createKeyStore(is.readAllBytes(), null);
        Client resteasyClient = ClientBuilder.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .trustStore(trustStore)
                .hostnameVerifier(new DefaultHostnameVerifier())
                .build();

        Keycloak keycloak = KeycloakBuilder.builder()
                .serverUrl(keyCloakContainer.getOrigin(KeyCloakContainer.Service.HTTPS))
                .realm("solace")
                .clientId("solace")
                .clientSecret("solace-secret")
                .grantType("client_credentials")
                .resteasyClient(resteasyClient)
                .build();
        return keycloak.tokenManager().getAccessTokenString();
    }

    private MessagingService getMessagingService() throws IOException {
        Properties properties = new Properties();
        properties.put(SolaceProperties.TransportLayerProperties.HOST,
                solaceContainer.getOrigin(SolaceContainer.Service.SMF_SSL));
        properties.put(SolaceProperties.ServiceProperties.VPN_NAME, solaceContainer.getVpn());
        properties.put(SolaceProperties.AuthenticationProperties.SCHEME, "AUTHENTICATION_SCHEME_OAUTH2");
        properties.put(SolaceProperties.TransportLayerSecurityProperties.CERT_VALIDATED, "false");
        properties.put(SolaceProperties.TransportLayerSecurityProperties.CERT_VALIDATE_SERVERNAME, "false");
        properties.put(SolaceProperties.AuthenticationProperties.SCHEME_OAUTH2_ACCESS_TOKEN, getAccessToken());

        MessagingService messagingService = MessagingService.builder(ConfigurationProfile.V1)
                .fromProperties(properties)
                .build();
        messagingService.connect();

        return messagingService;
    }

    @Test
    void oauthTest() throws IOException {
        MapBasedConfig config = new MapBasedConfig()
                .with("channel-name", "in")
                .with("consumer.queue.name", "queue-" + UUID.randomUUID().getMostSignificantBits())
                .with("consumer.queue.add-additional-subscriptions", true)
                .with("consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("consumer.subscriptions", SolaceContainer.INTEGRATION_TEST_QUEUE_SUBSCRIPTION);

        MessagingService messagingService = getMessagingService();
        SolaceIncomingChannel solaceIncomingChannel = new SolaceIncomingChannel(Vertx.vertx(), UnsatisfiedInstance.instance(),
                new SolaceConnectorIncomingConfiguration(config), messagingService);

        CopyOnWriteArrayList<Object> list = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Object> ackedMessageList = new CopyOnWriteArrayList<>();

        Flow.Publisher<? extends Message<?>> stream = solaceIncomingChannel.getStream();
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        Multi.createFrom().publisher(stream).subscribe().with(message -> {
            list.add(message);
            executorService.schedule(() -> {
                ackedMessageList.add(message);
                CompletableFuture.runAsync(message::ack);
            }, 1, TimeUnit.SECONDS);
        });

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(SolaceContainer.INTEGRATION_TEST_QUEUE_SUBSCRIPTION);
        publisher.publish("1", tp);
        publisher.publish("2", tp);
        publisher.publish("3", tp);
        publisher.publish("4", tp);
        publisher.publish("5", tp);

        Awaitility.await().until(() -> list.size() == 5);
        // Assert on acknowledged messages
        solaceIncomingChannel.close();
        Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> ackedMessageList.size() == 5);
        executorService.shutdown();
    }

}
