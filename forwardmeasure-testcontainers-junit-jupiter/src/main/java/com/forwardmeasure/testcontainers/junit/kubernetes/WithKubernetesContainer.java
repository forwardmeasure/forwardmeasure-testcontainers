package com.forwardmeasure.testcontainers.junit.kubernetes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/** Starts one single-node K3s container for the annotated JUnit test class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(KubernetesContainerExtension.class)
public @interface WithKubernetesContainer {

  String image() default "rancher/k3s:v1.36.4-k3s1";
}
