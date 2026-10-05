package group12.Services;

import group12.Entities.OrderEntity;
import group12.Repository.OrderRepository;
import group12.dto.CreateOrderRequest;
import group12.exception.OrderSubmissionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderSubmissionService {

    private final OrderRepository orderRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderEntity submit(CreateOrderRequest request) {
        OrderEntity order = new OrderEntity();
        order.setClientId(request.clientId());
        order.setInstrumentId(request.instrumentId());
        order.setOrderType(request.orderType());
        order.setQuantity(request.quantity());

        int rowsInserted = orderRepository.insert(order);
        if (rowsInserted != 1) {
            throw new OrderSubmissionException(
                    "Expected to insert one order but inserted " + rowsInserted
            );
        }

        return orderRepository.findById(order.getOrderId())
                .orElseThrow(() -> new OrderSubmissionException(
                        "Submitted order could not be reloaded"
                ));
    }
}
