package group12.Services;

import group12.dto.CreateOrderRequest;
import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Repository.OrderRepository;
import group12.exception.OrderNotFoundException;
import group12.exception.RetryableOrderExecutionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderSubmissionService orderSubmissionService;
    private final OrderAcceptanceService orderAcceptanceService;
    private final OrderExecutionService orderExecutionService;

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
            return orderExecutionService.executeAcceptedOrder(submittedOrder.getOrderId());
        } catch (RetryableOrderExecutionException exception) {
            return acceptedOrder;
        }
    }

}
