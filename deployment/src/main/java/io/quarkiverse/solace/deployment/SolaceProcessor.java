package io.quarkiverse.solace.deployment;

import java.util.Optional;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import org.jboss.jandex.*;

import com.solace.messaging.MessagingService;
import com.solacesystems.jcsmp.JCSMPFactory;

import io.quarkiverse.solace.MessagingServiceClientCustomizer;
import io.quarkiverse.solace.runtime.SolaceConfig;
import io.quarkiverse.solace.runtime.SolaceRecorder;
import io.quarkiverse.solace.runtime.observability.SolaceMetricBinder;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeansRuntimeInitBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.*;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ExtensionSslNativeSupportBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.metrics.MetricsCapabilityBuildItem;
import io.quarkus.runtime.metrics.MetricsFactory;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

class SolaceProcessor {

    private static final String FEATURE = "solace";

    private static final ParameterizedType SOLACE_CUSTOMIZER_INJECTION_TYPE = ParameterizedType.create(
            DotName.createSimple(Instance.class),
            new Type[] { ClassType.create(DotName.createSimple(MessagingServiceClientCustomizer.class.getName())) }, null);

    private static final AnnotationInstance[] EMPTY_ANNOTATIONS = new AnnotationInstance[0];

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void registerBean(BuildProducer<UnremovableBeanBuildItem> producer) {
        producer.produce(UnremovableBeanBuildItem.beanTypes(MessagingServiceClientCustomizer.class));
    }

    @BuildStep
    ExtensionSslNativeSupportBuildItem ssl() {
        return new ExtensionSslNativeSupportBuildItem(FEATURE);
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    ServiceStartBuildItem init(
            SolaceConfig config, SolaceRecorder recorder,
            ShutdownContextBuildItem shutdown, BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {

        Function<SyntheticCreationalContext<MessagingService>, MessagingService> function = recorder.init(config, shutdown);

        SyntheticBeanBuildItem.ExtendedBeanConfigurator solaceConfigurator = SyntheticBeanBuildItem
                .configure(MessagingService.class)
                .defaultBean()
                .scope(ApplicationScoped.class)
                .addInjectionPoint(SOLACE_CUSTOMIZER_INJECTION_TYPE, EMPTY_ANNOTATIONS)
                .createWith(function)
                .unremovable()
                .setRuntimeInit();

        syntheticBeans.produce(solaceConfigurator.done());

        return new ServiceStartBuildItem(FEATURE);
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    @Consume(SyntheticBeansRuntimeInitBuildItem.class)
    @Consume(SolaceBuildItem.class)
    void initMetrics(SolaceBuildTimeConfig btConfig, Optional<MetricsCapabilityBuildItem> metrics,
            SolaceMetricBinder metricRecorder) {
        if (metrics.isPresent() && btConfig.metrics().enabled()) {
            if (metrics.get().metricsSupported(MetricsFactory.MICROMETER)) {
                metricRecorder.initMetrics();
            }
        }
    }

    @BuildStep
    void configureNativeCompilation(BuildProducer<RuntimeInitializedClassBuildItem> producer) {
        producer.produce(new RuntimeInitializedClassBuildItem(JCSMPFactory.class.getName()));
    }

    @BuildStep
    HealthBuildItem addHealthCheck(SolaceBuildTimeConfig buildTimeConfig) {
        return new HealthBuildItem("io.quarkiverse.solace.runtime.observability.SolaceHealthCheck",
                buildTimeConfig.health().enabled());
    }

}
