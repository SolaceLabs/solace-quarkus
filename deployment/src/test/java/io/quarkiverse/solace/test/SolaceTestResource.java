package io.quarkiverse.solace.test;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class SolaceTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";
    private SolaceContainer container;

    @Override
    public Map<String, String> start() {
        container = new SolaceContainer(SOLACE_IMAGE)
                .withCredentials("user", "pass")
                .withTopic("hello/direct", SolaceContainer.Service.SMF)
                .withTopic("hello/persistent", SolaceContainer.Service.SMF);
        container.start();
        return Map.of("quarkus.solace.host", container.getHost() + ":" + container.getMappedPort(55555),
                "quarkus.solace.vpn", "default",
                "quarkus.solace.authentication.basic.username", "user",
                "quarkus.solace.authentication.basic.password", "pass");
    }

    @Override
    public void stop() {
        container.stop();
    }
}
