package com.solace.quarkus.messaging.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

public class SolaceMessagingProcessor {

    public static final String FEATURE = "solace-messaging-connector";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

}
