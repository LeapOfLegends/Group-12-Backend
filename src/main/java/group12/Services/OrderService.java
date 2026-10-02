package group12.Services;

import group12.dto.CreateOrderRequest;
import group12.Entities.OrderEntity;
import group12.Repository.OrderRepository;
import group12.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
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
        return orderAcceptanceService.acceptSubmittedOrder(submittedOrder.getOrderId());
    }

}
