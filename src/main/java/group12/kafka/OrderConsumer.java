package group12.kafka;

import group12.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import static group12.config.KafkaOrderConfiguration.*;

/**
 * CONSUMER: Listens to order events published by OrderService
 * 
 * Event Flow:
 * 1. Order SUBMITTED by client → OrderSubmissionService creates order in DB
 * 2. Order ACCEPTED by OrderAcceptanceService after validation → OrderAcceptedEvent published
 *    - Validates instrument is tradable
 *    - Validates quote freshness
 *    - Validates sufficient funds (BUY) or holdings (SELL)
 * 3. Order FILLED by OrderExecutionService after execution → OrderFilledEvent published
 *    - For BUY: Deducts cash, updates holdings, sets execution price
 *    - For SELL: Removes holdings, adds cash, sets execution price
 */
@Slf4j
@Service
public class OrderConsumer {
    
    /**
     * Handles ACCEPTED orders
     * 
     * At this point:
     * - Order status changed from SUBMITTED → ACCEPTED
     * - All validation checks passed (instrument, quote, funds/holdings)
     * - Order is ready for execution
     * - Reserved cash/holdings are locked
     */
    @KafkaListener(topics = ORDER_ACCEPTED_TOPIC, groupId = "order-group")
    public void handleOrderAccepted(OrderAcceptedEvent event) {
        log.info("ORDER ACCEPTED: orderId={}, clientId={}, acceptedAt={}", 
                event.getOrderId(), event.getClientId(), event.getAcceptedAt());
        
        // Order passed all validation checks and is now ACCEPTED
        // Business logic examples (currently just logging):
        // - Could trigger automatic execution
        // - Could update real-time dashboard showing accepted orders
        // - Could start timer for time-in-force requirements
        // - Could update trading UI with confirmed order
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
