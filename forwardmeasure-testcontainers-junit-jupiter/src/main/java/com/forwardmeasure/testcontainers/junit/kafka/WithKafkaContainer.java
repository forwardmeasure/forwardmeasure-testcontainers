package com.forwardmeasure.testcontainers.junit.kafka;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/** Starts one Kafka container for the annotated JUnit test class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(KafkaContainerExtension.class)
public @interface WithKafkaContainer {

  String image() default "apache/kafka:4.3.1";

  /**
   * See {@code KafkaContainerConfiguration#withHostDockerInternalListener}'s own javadoc - needed
   * only by a test whose real consumer (e.g. a K3s pod via {@code pod.spec.hostAliases}) can
   * resolve {@code host.docker.internal} but not "localhost", which is what every other listener
   * advertises by default.
   */
  boolean hostDockerInternalListener() default false;
}
