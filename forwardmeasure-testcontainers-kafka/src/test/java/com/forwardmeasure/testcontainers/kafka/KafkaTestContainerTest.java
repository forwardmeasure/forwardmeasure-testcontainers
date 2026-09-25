package com.forwardmeasure.testcontainers.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

class KafkaTestContainerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaTestContainerTest.class);

  @Test
  void startsRealKafkaAndRoundTripsARealMessage() throws Exception {
    try (var kafka = new KafkaTestContainer().start()) {
      LOGGER.info("Started {} at {}", kafka.containerName(), kafka.bootstrapServers());
      assertTrue(kafka.isRunning());

      String topic = "kafka-test-container-" + UUID.randomUUID();
      try (Admin admin =
          Admin.create(
              Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()))) {
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
      }

      Properties producerProps = new Properties();
      producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
      producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
      producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
      try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
        producer.send(new ProducerRecord<>(topic, "key-1", "hello-kafka-testcontainer")).get();
      }

      Properties consumerProps = new Properties();
      consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
      consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-test-container-test");
      consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
        consumer.subscribe(List.of(topic));
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(30));
        LOGGER.info("Polled {} record(s) from topic {}", records.count(), topic);
        assertEquals(1, records.count());
        assertEquals("hello-kafka-testcontainer", records.iterator().next().value());
      }
    }
  }

  /**
   * Real, no-mocks proof that a caller-supplied network + alias genuinely lets a real sibling
   * container reach this Kafka broker at {@code networkBootstrapServers()} - the exact combination
   * ({@link KafkaContainerConfiguration#withNetwork}, {@link #networkBootstrapServers()}) no test
   * in this module ever exercised together before this test was added, which let a real bug reach
   * live use unnoticed: {@code withListener(alias + ':' + KAFKA_PORT)} reused port 9092, the same
   * port the real image's own default {@code PLAINTEXT} listener already binds - {@code
   * withListener} only ever adds a listener, never replaces the default one, so the container
   * failed to start at all ({@code "Each listener must have a different port"}) the first time this
   * path was actually exercised live (2026-09-21, building forwardmeasure-data-streaming's own
   * Phase D fixture). Fixed by giving network-alias listeners their own port range, starting at
   * {@link #FIRST_NETWORK_LISTENER_PORT_FOR_TEST} - this test proves that fix, not just that it
   * compiles.
   */
  @Test
  void networkAliasIsReallyReachableFromARealSiblingContainer() throws Exception {
    String alias = "kafka-network-alias-test";
    try (Network network = Network.newNetwork();
        var kafka =
            new KafkaTestContainer(
                    KafkaContainerConfiguration.defaults()
                        .withNetwork(network.getId(), List.of(alias)))
                .start()) {
      LOGGER.info(
          "Started {} - host endpoint {}, network endpoint {}",
          kafka.containerName(),
          kafka.bootstrapServers(),
          kafka.networkBootstrapServers());
      assertEquals(
          alias + ':' + FIRST_NETWORK_LISTENER_PORT_FOR_TEST, kafka.networkBootstrapServers());

      String topic = "kafka-network-alias-test-" + UUID.randomUUID();
      try (Admin admin =
          Admin.create(
              Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()))) {
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
      }

      try (org.testcontainers.containers.GenericContainer<?> client =
          new org.testcontainers.containers.GenericContainer<>(
                  DockerImageName.parse("apache/kafka:4.3.1"))
              .withNetwork(network)
              .withCommand("sleep", "infinity")) {
        client.start();
        org.testcontainers.containers.Container.ExecResult result =
            client.execInContainer(
                "/opt/kafka/bin/kafka-topics.sh",
                "--bootstrap-server",
                kafka.networkBootstrapServers(),
                "--list");
        LOGGER.info(
            "kafka-topics.sh --bootstrap-server {} --list (exit {}): stdout={} stderr={}",
            kafka.networkBootstrapServers(),
            result.getExitCode(),
            result.getStdout(),
            result.getStderr());
        assertEquals(
            0,
            result.getExitCode(),
            "a real sibling container must reach the broker via its own network alias");
        assertTrue(result.getStdout().contains(topic));
      }
    }
  }

  private static final int FIRST_NETWORK_LISTENER_PORT_FOR_TEST = 9095;

  @Test
  void refusesEndpointsBeforeStartup() {
    try (var kafka = new KafkaTestContainer()) {
      LOGGER.info("Confirming bootstrapServers() rejects a not-yet-started container");
      assertThrows(IllegalStateException.class, kafka::bootstrapServers);
    }
  }
}
