package group12.Services;

import group12.dto.CreateOrderRequest;
import group12.Entities.OrderEntity;
import group12.Repository.OrderRepository;
import group12.exception.OrderNotFoundException;
import group12.exception.OrderSubmissionException;
import group12.exception.ClientNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderEntity getOrderById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
    }

    public List<OrderEntity> getOrdersByClientId(Long clientId) {
        return orderRepository.findByClientId(clientId);
    }

    public OrderEntity submitOrder(CreateOrderRequest request) {

        OrderEntity order = new OrderEntity();
        order.setClientId(request.clientId());
        order.setInstrumentId(request.instrumentId());
        order.setOrderType(request.orderType());
        order.setQuantity(request.quantity());

        int rowsInserted = orderRepository.insert(order);

        if (rowsInserted != 1) {
            throw new OrderSubmissionException("Order could not be created");
        }

        return getOrderById(order.getOrderId());
    }

}