package com.agent.session.bus;

import com.agent.session.kafka.KafkaEventPublisher;
import com.agent.session.nats.NatsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledIfEnvironmentVariable(named = "SESSION_BUS_IT", matches = "true")
class SessionBusLinkTest {

    @Test
    void natsRequestResponseFlowsThroughBusProxy() throws Exception {
        String natsUrl = System.getenv().getOrDefault("NATS_URL", "nats://127.0.0.1:4222");
        String subject = "lifeform.rpc.session.integration." + UUID.randomUUID();
        Connection responder = Nats.connect(natsUrl);
        Dispatcher dispatcher = responder.createDispatcher(message -> {
            if (message.getReplyTo() != null) {
                responder.publish(message.getReplyTo(), "pong".getBytes(StandardCharsets.UTF_8));
            }
        });
        dispatcher.subscribe(subject);

        NatsClient natsClient = new NatsClient();
        natsClient.init();
        try {
            BusProxy bus = new BusProxy(natsClient, new ObjectMapper());
            assertEquals("pong", bus.request(subject, Map.of("tenant_id", "tenant-a")));
        } finally {
            natsClient.close();
            responder.close();
        }
    }

    @Test
    void kafkaPublisherWritesToRealBroker() {
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "127.0.0.1:9092");
        String topic = "lifeform.session.integration";
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "session-bus-link-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        KafkaEventPublisher publisher = new KafkaEventPublisher(new ObjectMapper());
        publisher.init();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            publisher.publish("session", "integration", "route-test", Map.of("event", "created"));
            long deadline = System.currentTimeMillis() + 10_000;
            ConsumerRecord<String, String> received = null;
            while (System.currentTimeMillis() < deadline && received == null) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if ("route-test".equals(record.key())) {
                        received = record;
                        break;
                    }
                }
            }
            assertNotNull(received, "Kafka publisher must publish to real broker");
            assertEquals("lifeform.session.integration", received.topic());
        } finally {
            publisher.close();
        }
    }
}