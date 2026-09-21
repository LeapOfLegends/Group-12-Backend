package group12.Services;

import group12.dto.CreateOrderRequest;
import group12.Entities.OrderEntity;
import group12.Repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderEntity getOrderById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Order not found"
                        )
                );
    }

    public List<OrderEntity> getOrdersByClientId(Long clientId) {
        return orderRepository.findByClientId(clientId);
    }

    public OrderEntity submitOrder(CreateOrderRequest request) {

        validateSubmission(request);

        OrderEntity order = new OrderEntity();

        order.setClientId(request.clientId());
        order.setInstrumentId(request.instrumentId());
        order.setOrderType(request.orderType());
        order.setQuantity(request.quantity());

        int rowsInserted = orderRepository.insert(order);

        if (rowsInserted != 1) {
            throw new IllegalStateException("Order could not be created");
        }

        return getOrderById(order.getOrderId());
    }

    private void validateSubmission(CreateOrderRequest request) {

        if (request.clientId() == null) {
            throw new IllegalArgumentException("Client ID is required");
        }

        if (request.instrumentId() == null) {
            throw new IllegalArgumentException("Instrument ID is required");
        }

        if (request.orderType() == null) {
            throw new IllegalArgumentException("Order type is required");
        }

        if (request.quantity() == null || request.quantity() <= 0) {
            throw new IllegalArgumentException(
                    "Quantity must be greater than zero"
            );
        }
    }
}