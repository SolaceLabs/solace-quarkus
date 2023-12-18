package io.quarkiverse.solace.base;

import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.time.Duration;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class SolaceBrokerExtension implements BeforeAllCallback, ParameterResolver, CloseableResource {

    public static final Logger LOGGER = Logger.getLogger(SolaceBrokerExtension.class.getName());
    private static final String SOLACE_READY_MESSAGE = ".*Primary Virtual Router is now active.*";
    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";

    protected SolaceContainer solace;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        ExtensionContext.Store globalStore = context.getRoot().getStore(GLOBAL);
        SolaceBrokerExtension extension = (SolaceBrokerExtension) globalStore.get(SolaceBrokerExtension.class);
        if (extension == null) {
            LOGGER.info("Starting Solace broker");
            startSolaceBroker();
            globalStore.put(SolaceBrokerExtension.class, this);
        }
    }

    @Override
    public void close() throws Throwable {

    }

    public static SolaceContainer createSolaceContainer() {
        return new SolaceContainer(SOLACE_IMAGE);
    }

    public void startSolaceBroker() {
        solace = createSolaceContainer()
                .withCredentials("user", "pass")
                .withExposedPorts(SolaceContainer.Service.SMF.getPort())
                .withPublishTopic("quarkus/integration/test/default/topic", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/provisioned/queue/topic", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/provisioned/queue/error/topic", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/dynamic/topic/1", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/dynamic/topic/2", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/dynamic/topic/3", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/dynamic/topic/4", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/dynamic/topic/5", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/default/topic-processed", SolaceContainer.Service.SMF);
        solace.start();
        LOGGER.info("Solace broker started: " + solace.getOrigin(SolaceContainer.Service.SMF));
        await().until(() -> solace.isRunning());
    }

    /**
     * We need to restart the broker on the same exposed port.
     * Test Containers makes this unnecessarily complicated, but well, let's go for a hack.
     * See https://github.com/testcontainers/testcontainers-java/issues/256.
     *
     * @param solace the broker that will be closed
     * @param gracePeriodInSecond number of seconds to wait before restarting
     * @return the new broker
     */
    public static SolaceContainer restart(SolaceContainer solace, int gracePeriodInSecond) {
        int port = solace.getMappedPort(SolaceContainer.Service.SMF.getPort());
        try {
            solace.close();
        } catch (Exception e) {
            // Ignore me.
        }
        await().until(() -> !solace.isRunning());
        sleep(Duration.ofSeconds(gracePeriodInSecond));

        return startSolaceBroker(port);
    }

    public static SolaceContainer startSolaceBroker(int port) {
        SolaceContainer solace = createSolaceContainer();
        solace.start();
        await().until(solace::isRunning);
        return solace;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stopSolaceBroker() {
        if (solace != null) {
            try {
                solace.stop();
            } catch (Exception e) {
                // Ignore it.
            }
            await().until(() -> !solace.isRunning());
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType().equals(SolaceContainer.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        ExtensionContext.Store globalStore = extensionContext.getRoot().getStore(GLOBAL);
        SolaceBrokerExtension extension = (SolaceBrokerExtension) globalStore.get(SolaceBrokerExtension.class);
        if (parameterContext.getParameter().getType().equals(SolaceContainer.class)) {
            return extension.solace;
        }
        return null;
    }
}
