package group12.kafka;

import group12.Services.OrderExecutionService;
import group12.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import static group12.config.KafkaOrderConfiguration.*;

/**
 * CONSUMER: Listens to order events and processes them asynchronously
 * 
 * Event Flow:
 * 1. OrderService publishes OrderAcceptedEvent to Kafka
 * 2. OrderConsumer receives the event via handleOrderAccepted
 * 3. OrderExecutionService executes the order asynchronously
 * 4. Order status changes from ACCEPTED to FILLED
 * 5. OrderExecutionService publishes OrderFilledEvent
 * 6. OrderConsumer receives and logs the completion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderConsumer {
    
    private final OrderExecutionService orderExecutionService;
    
    /**
     * Handles ACCEPTED orders - executes them asynchronously
     * 
     * At this point:
     * - Order status is ACCEPTED
     * - All validation checks passed (instrument, quote, funds/holdings)
     * - Reserved cash/holdings are locked
     * 
     * This consumer executes the order immediately after acceptance
     */
    @KafkaListener(topics = ORDER_ACCEPTED_TOPIC, groupId = "order-group")
    public void handleOrderAccepted(OrderAcceptedEvent event) {
        log.info("ORDER ACCEPTED: orderId={}, clientId={}, acceptedAt={}", 
                event.getOrderId(), event.getClientId(), event.getAcceptedAt());
        
        try {
            log.info("Executing accepted order: orderId={}", event.getOrderId());
            orderExecutionService.executeAcceptedOrder(event.getOrderId());
            log.info("Order execution completed: orderId={}", event.getOrderId());
            
        } catch (Exception e) {
            log.error("Error executing order: orderId={}, error={}", 
                    event.getOrderId(), e.getMessage(), e);
        }
    }
    
    /**
     * Handles FILLED orders (executed/completed)
     * 
     * At this point:
     * - Order status changed from ACCEPTED → FILLED
     * - Trade has been EXECUTED at a specific price
     * - For BUY orders: Cash was deducted, holdings were updated
     * - For SELL orders: Holdings were removed, cash was credited
     * - Execution is permanent and recorded
     */
    @KafkaListener(topics = ORDER_FILLED_TOPIC, groupId = "order-group")
    public void handleOrderFilled(OrderFilledEvent event) {
        log.info("ORDER FILLED: orderId={}, clientId={}, qty={}, executionPrice={}, filledAt={}", 
                event.getOrderId(), event.getClientId(), event.getQuantityFilled(), 
                event.getExecutionPrice(), event.getFilledAt());
        
        // Order has been EXECUTED and FILLED
        // Business logic examples (currently just logging):
        // - Send trade confirmation to client (email/SMS/push)
        // - Update account holdings immediately in UI
        // - Update cash balance in UI
        // - Log trade for compliance/audit
        // - Update trading history
        // - Trigger post-trade settlement processes
        // - Calculate P&L
    }
}
