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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.testcontainers.kubernetes.KubernetesTestContainer.ImageCommandResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImagePinningContractTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void registryPortsAndDigestReferencesKeepTheCorrectRepository() {
    for (String ref :
        List.of(
            "registry.example:5000/team/app:v1",
            "registry.example:5000/team/app@" + DIGEST,
            "registry.example:5000/team/app")) {
      var calls = new ArrayList<List<String>>();
      var pinned =
          KubernetesTestContainer.pinDigest(
              ref,
              command -> {
                calls.add(List.of(command));
                return command[2].equals("ls")
                    ? success(ref + " application/vnd.oci.image.manifest.v1+json " + DIGEST)
                    : success("");
              });
      assertEquals("registry.example:5000/team/app@" + DIGEST, pinned);
      assertEquals(List.of("ctr", "images", "tag", ref, pinned), calls.get(1));
      assertEquals(2, calls.size());
    }
  }

  @Test
  void normalizedLocalImageIsTaggedOnlyWithItsOwnValidDigest() {
    var calls = new ArrayList<List<String>>();
    String pinned =
        KubernetesTestContainer.pinDigest(
            "team/app:v1",
            command -> {
              calls.add(List.of(command));
              return command[2].equals("ls")
                  ? success(
                      "REF TYPE DIGEST\n\n"
                          + "docker.io/unrelated/app:v1 type sha256:"
                          + "b".repeat(64)
                          + "\n"
                          + "docker.io/team/app:v1 type "
                          + DIGEST)
                  : success("");
            });
    assertEquals("docker.io/team/app@" + DIGEST, pinned);
    assertEquals(List.of("ctr", "images", "tag", "docker.io/team/app:v1", pinned), calls.get(1));
  }

  @Test
  void absentOrMalformedDigestNeverFabricatesAPinnedReference() {
    for (String listing :
        List.of("", "unrelated:v1 type " + DIGEST, "team/app:v1 type sha256:invalid")) {
      var calls = new ArrayList<List<String>>();
      var failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  KubernetesTestContainer.pinDigest(
                      "team/app:v1",
                      command -> {
                        calls.add(List.of(command));
                        return success(listing);
                      }));
      assertTrue(failure.getMessage().contains("Could not find"));
      assertEquals(List.of(List.of("ctr", "images", "ls")), calls);
    }
  }

  @Test
  void listingAndTaggingFailuresCannotBeReportedAsSuccessfulPins() {
    for (String failing : List.of("ls", "tag")) {
      var failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  KubernetesTestContainer.pinDigest(
                      "team/app:v1",
                      command ->
                          command[2].equals(failing)
                              ? new ImageCommandResult(1, "", "containerd refused command")
                              : success("team/app:v1 type " + DIGEST)));
      assertTrue(failure.getMessage().contains("ctr images " + failing + " failed"));
      assertTrue(failure.getMessage().contains("containerd refused command"));
    }
  }

  @Test
  void transportFailureRetainsCauseAndInterruptionRetainsThreadStatus() {
    var cause = new IOException("connection lost");
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                KubernetesTestContainer.pinDigest(
                    "team/app:v1",
                    command -> {
                      throw cause;
                    }));
    assertSame(cause, failure.getCause());
    var interrupted = new InterruptedException("cancelled");
    try {
      failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  KubernetesTestContainer.pinDigest(
                      "team/app:v1",
                      command -> {
                        throw interrupted;
                      }));
      assertSame(interrupted, failure.getCause());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private static ImageCommandResult success(String output) {
    return new ImageCommandResult(0, output, "");
  }
}
