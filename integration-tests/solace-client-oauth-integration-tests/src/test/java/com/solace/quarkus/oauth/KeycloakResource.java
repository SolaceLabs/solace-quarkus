package com.solace.quarkus.oauth;

import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Map;

import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.utility.MountableFile;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class KeycloakResource implements QuarkusTestResourceLifecycleManager {
    KeyCloakContainer keyCloakContainer;
    private SolaceContainer solaceContainer;

    @Override
    public Map<String, String> start() {
        writeJks();

        keyCloakContainer = new KeyCloakContainer();
        keyCloakContainer.start();
        keyCloakContainer.createHostsFile();
        await().until(() -> keyCloakContainer.isRunning());
        solaceContainer = new SolaceContainer("solace/solace-pubsub-standard:latest");
        solaceContainer.withCredentials("user", "pass")
                .withClientCert(MountableFile.forClasspathResource("solace.pem"),
                        MountableFile.forClasspathResource("keycloak.crt"), false)
                .withOAuth()
                .withExposedPorts(SolaceContainer.Service.SMF.getPort(), SolaceContainer.Service.SMF_SSL.getPort(), 1943, 8080)
                .withPublishTopic("hello/direct", SolaceContainer.Service.SMF)
                .withPublishTopic("hello/persistent", SolaceContainer.Service.SMF);

        solaceContainer.start();
        Awaitility.await().until(() -> solaceContainer.isRunning());

        return Map.ofEntries(
                Map.entry("quarkus.oidc-client.solace.auth-server-url",
                        keyCloakContainer.getOrigin(KeyCloakContainer.Service.HTTPS) + "/realms/solace"),
                Map.entry("quarkus.oidc-client.solace.tls.trust-store-file", "target/keycloak.jks"),
                Map.entry("quarkus.oidc-client.solace.tls.key-store-password", "password"),
                Map.entry("quarkus.oidc-client.solace.client-id", "solace"),
                Map.entry("quarkus.oidc-client.solace.credentials.secret", "solace-secret"),
                Map.entry("quarkus.oidc-client.solace.tls.verification", "none"),
                //                Map.entry("quarkus.oidc-client.solace.refresh-token-time-skew", "5s"),
                Map.entry("quarkus.solace.host", solaceContainer.getOrigin(SolaceContainer.Service.SMF_SSL)),
                Map.entry("quarkus.solace.vpn", solaceContainer.getVpn()),
                Map.entry("quarkus.solace.oidc.refresh.interval", "5s"),
                Map.entry("quarkus.solace.oidc.client-name", "solace"),
                Map.entry("quarkus.solace.authentication.scheme", "AUTHENTICATION_SCHEME_OAUTH2"),
                Map.entry("quarkus.solace.tls.cert-validated", "false"),
                Map.entry("quarkus.solace.tls.cert-validate-servername", "false"));
    }

    private static void writeJks() {
        try (var fis = new FileInputStream(KeycloakResource.class.getResource("/keycloak.crt").getFile());
                var fos = new FileOutputStream("target/keycloak.jks")) {
            createKeyStore(fis.readAllBytes(), null).store(fos, "password".toCharArray());
        } catch (IOException | CertificateException | KeyStoreException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
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

    @Override
    public void stop() {
        keyCloakContainer.stop();
        solaceContainer.stop();
    }

}