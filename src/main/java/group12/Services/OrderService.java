package group12.Services;

import group12.dto.CreateOrderRequest;
import group12.dto.OrderAcceptedEvent;
import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Repository.OrderRepository;
import group12.exception.OrderNotFoundException;
import group12.exception.RetryableOrderExecutionException;
import group12.kafka.OrderProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderProducer orderProducer;
    private final OrderSubmissionService orderSubmissionService;
    private final OrderAcceptanceService orderAcceptanceService;


    public OrderEntity getOrderById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
    }

    public List<OrderEntity> getOrdersByClientId(Long clientId) {
        return orderRepository.findByClientId(clientId);
    }

    public OrderEntity submitOrder(CreateOrderRequest request) {
        OrderEntity submittedOrder = orderSubmissionService.submit(request);
        OrderEntity acceptedOrder = orderAcceptanceService.acceptSubmittedOrder(
                submittedOrder.getOrderId()
        );

        if (acceptedOrder.getStatus() != OrderStatus.ACCEPTED) {
            return acceptedOrder;
        }

        try {
            // Map OrderEntity to OrderAcceptedEvent
            OrderAcceptedEvent event = new OrderAcceptedEvent(
                acceptedOrder.getOrderId(),
                acceptedOrder.getClientId(),
                LocalDateTime.now()
            );
            
            // Publish event to Kafka
            orderProducer.publishOrderAccepted(event);
            
            // Return the OrderEntity
            return acceptedOrder;
            
        } catch (RetryableOrderExecutionException exception) {
            return acceptedOrder;
        }
        
    }

}
