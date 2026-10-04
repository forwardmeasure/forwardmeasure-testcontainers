/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.forwardmeasure.testcontainers.kubernetes;

import java.util.Objects;
import org.testcontainers.utility.DockerImageName;

/** Immutable configuration for one K3s test container. */
public record KubernetesContainerConfiguration(DockerImageName image) {

  public static final DockerImageName DEFAULT_IMAGE =
      DockerImageName.parse("rancher/k3s:v1.36.4-k3s1");

  public KubernetesContainerConfiguration {
    Objects.requireNonNull(image, "image");
  }

  public static KubernetesContainerConfiguration defaults() {
    return new KubernetesContainerConfiguration(DEFAULT_IMAGE);
  }
}
