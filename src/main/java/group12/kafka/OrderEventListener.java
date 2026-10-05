package group12.kafka;

import group12.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import static group12.config.KafkaOrderConfiguration.*;

/**
 * CONSUMER: Listens to order events published by OrderService
 * 
 * This is an example of what a consumer does:
 * - Listens to Kafka topics
 * - Reacts to events
 * - Can trigger additional business logic
 * 
 * In your case, when an order is ACCEPTED, you might want to:
 * 1. Execute it immediately (call execution service)
 * 2. Send notification to client
 * 3. Log for audit trail
 * 4. Update analytics
 */
@Slf4j
@Service
public class OrderEventListener {
    
    /**
     * STEP 1: Consumer receives OrderAcceptedEvent from Kafka
     * 
     * Flow:
     * OrderService publishes OrderAcceptedEvent
     *     ↓
     * Kafka stores it in "ORDER_ACCEPTED_TOPIC"
     *     ↓
     * OrderEventListener listens (@KafkaListener)
     *     ↓
     * handleOrderAccepted() method is called automatically
     *     ↓
     * Log the event / Process business logic
     */
    @KafkaListener(topics = ORDER_ACCEPTED_TOPIC, groupId = "order-group")
    public void handleOrderAccepted(OrderAcceptedEvent event) {
        log.info("[CONSUMER] Received ORDER ACCEPTED event: orderId={}, clientId={}", 
                event.getOrderId(), event.getClientId());
        
        // Example: What you could do here
        // - Execute the order immediately
        // - Send confirmation email
        // - Update real-time dashboard
        // - Start matching engine
        // - Update holdings preview
    }
    
    /**
     * STEP 2: Consumer receives OrderFilledEvent from Kafka
     * 
     * This would be called when order execution is complete
     */
    @KafkaListener(topics = ORDER_FILLED_TOPIC, groupId = "order-group")
    public void handleOrderFilled(OrderFilledEvent event) {
        log.info("[CONSUMER] Received ORDER FILLED event: orderId={}, clientId={}, qty={}, price={}", 
                event.getOrderId(), event.getClientId(), event.getQuantityFilled(), event.getExecutionPrice());
        
        // Example: What you could do here
        // - Update account holdings
        // - Update cash balance
        // - Send execution confirmation
        // - Log trade for compliance
    }
}
