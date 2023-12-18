package io.quarkiverse.solace.base;

import java.lang.reflect.Method;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

import com.solace.messaging.MessagingService;
import com.solace.messaging.config.SolaceProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;

@ExtendWith(SolaceBrokerExtension.class)
public class SolaceBaseTest {

    public static SolaceContainer solace;

    public static MessagingService messagingService;

    public String topic;

    public String queue;

    @BeforeEach
    public void initTopic(TestInfo testInfo) {
        String cn = testInfo.getTestClass().map(Class::getSimpleName).orElse(UUID.randomUUID().toString());
        String mn = testInfo.getTestMethod().map(Method::getName).orElse(UUID.randomUUID().toString());
//        topic = cn + "/" + mn + "/" + UUID.randomUUID().getMostSignificantBits();
        topic = "quarkus/integration/test/default/topic";
    }

    @BeforeEach
    public void initQueueName(TestInfo testInfo) {
        String cn = testInfo.getTestClass().map(Class::getSimpleName).orElse(UUID.randomUUID().toString());
        String mn = testInfo.getTestMethod().map(Method::getName).orElse(UUID.randomUUID().toString());
        queue = cn + "." + mn + "." + UUID.randomUUID().getMostSignificantBits();

    }

    @BeforeAll
    static void init(SolaceContainer container) {
        solace = container;
        Properties properties = new Properties();
        properties.put(SolaceProperties.TransportLayerProperties.HOST, solace.getOrigin(SolaceContainer.Service.SMF));
        properties.put(SolaceProperties.ServiceProperties.VPN_NAME, solace.getVpn());
        properties.put(SolaceProperties.AuthenticationProperties.SCHEME_BASIC_USER_NAME, solace.getUsername());
        properties.put(SolaceProperties.AuthenticationProperties.SCHEME_BASIC_PASSWORD, solace.getPassword());

        messagingService = MessagingService.builder(ConfigurationProfile.V1)
                .fromProperties(properties)
                .build();
        messagingService.connect();
        MessagingServiceProvider.messagingService = messagingService;
    }

}
