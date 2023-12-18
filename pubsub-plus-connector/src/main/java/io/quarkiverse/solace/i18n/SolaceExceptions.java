package io.quarkiverse.solace.i18n;

import org.jboss.logging.Messages;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageBundle;

/**
 * Exceptions for Kafka Connector
 * Assigned ID range is 55000-55099
 */
@MessageBundle(projectCode = "SRMSG", length = 5)
public interface SolaceExceptions {
    SolaceExceptions ex = Messages.getBundle(SolaceExceptions.class);

    @Message(id = 18000, value = "`message` does not contain metadata of class %s")
    IllegalArgumentException illegalArgument(Class c);

    @Message(id = 18001, value = "Only one subscriber allowed")
    IllegalStateException illegalStateOnlyOneSubscriber();

    @Message(id = 18002, value = "Expecting downstream to consume without back-pressure")
    IllegalStateException illegalStateConsumeWithoutBackPressure();

}
