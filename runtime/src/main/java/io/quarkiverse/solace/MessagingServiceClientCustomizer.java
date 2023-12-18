package io.quarkiverse.solace;

import com.solace.messaging.MessagingServiceClientBuilder;

/**
 * Interface implemented by CDI beans that want to customize the Solace client configuration.
 * <p>
 * Only one bean exposing this interface is allowed in the application.
 */
public interface MessagingServiceClientCustomizer {

    /**
     * Customizes the Solace client configuration.
     * <p>
     * This method is called during the creation of the Solace client instance.
     * The Quarkus configuration had already been processed.
     * It gives you the opportunity to extend that configuration or override the processed configuration.
     * <p>
     * Implementation can decide to ignore the passed builder to build their own.
     * However, this should be used with caution as it can lead to unexpected results.
     *
     * @param builder the builder to customize the Solace client instance
     * @return the builder to use to create the Solace client instance
     */
    MessagingServiceClientBuilder customize(MessagingServiceClientBuilder builder);
}
