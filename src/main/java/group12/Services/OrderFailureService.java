package group12.Services;

import group12.Entities.OrderEntity;
import group12.Repository.OrderRepository;
import group12.exception.OrderLifecycleException;
import group12.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderFailureService {

    private final OrderRepository orderRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long orderId, String failureReason) {
        int rowsAffected = orderRepository.failAcceptedOrder(orderId, failureReason);

        if (rowsAffected == 1) {
            return;
        }

        if (rowsAffected > 1) {
            throw new OrderLifecycleException(
                    "Expected to fail one order but updated " + rowsAffected + " rows"
            );
        }

        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));

        throw new OrderLifecycleException(
                "Order " + orderId
                        + " cannot transition from " + order.getStatus()
                        + " to FAILED"
        );
    }
}
