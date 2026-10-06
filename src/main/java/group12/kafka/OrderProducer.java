package group12.kafka;

import group12.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import static group12.config.KafkaOrderConfiguration.*;

/**
 * Simple producer for publishing order events to Kafka topics
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProducer {
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    public void publishOrderAccepted(OrderAcceptedEvent event) {
        try {
            kafkaTemplate.send(ORDER_ACCEPTED_TOPIC, String.valueOf(event.getOrderId()), event);
            log.info("Order accepted event published - orderId: {}", event.getOrderId());
        } catch (Exception e) {
            log.error("Failed to publish order accepted event", e);
        }
    }
    
    public void publishOrderFilled(OrderFilledEvent event) {
        try {
            kafkaTemplate.send(ORDER_FILLED_TOPIC, String.valueOf(event.getOrderId()), event);
            log.info("Order filled event published - orderId: {}", event.getOrderId());
        } catch (Exception e) {
            log.error("Failed to publish order filled event", e);
        }
    }
}
