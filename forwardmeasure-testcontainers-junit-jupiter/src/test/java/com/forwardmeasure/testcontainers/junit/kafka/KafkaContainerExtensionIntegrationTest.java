package com.forwardmeasure.testcontainers.junit.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.testcontainers.kafka.KafkaTestContainer;
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

@WithKafkaContainer
class KafkaContainerExtensionIntegrationTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(KafkaContainerExtensionIntegrationTest.class);

  @Test
  void startsRealKafkaAndInjectsTheOwnedContainer(KafkaTestContainer container) throws Exception {
    LOGGER.info(
        "@WithKafkaContainer injected {} at {}",
        container.containerName(),
        container.bootstrapServers());
    assertTrue(container.isRunning());

    String topic = "kafka-container-extension-test-" + UUID.randomUUID();
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, container.bootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
    }

    Properties producerProps = new Properties();
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, container.bootstrapServers());
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
      producer.send(new ProducerRecord<>(topic, "key-1", "hello-extension")).get();
    }

    Properties consumerProps = new Properties();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, container.bootstrapServers());
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-container-extension-test");
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
      consumer.subscribe(List.of(topic));
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(30));
      LOGGER.info("Polled {} record(s) from topic {}", records.count(), topic);
      assertEquals(1, records.count());
      assertEquals("hello-extension", records.iterator().next().value());
    }
  }
}
