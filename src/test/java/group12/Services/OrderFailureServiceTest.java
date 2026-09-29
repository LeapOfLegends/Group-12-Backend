package group12.Services;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Repository.OrderRepository;
import group12.exception.OrderLifecycleException;
import group12.exception.OrderNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderFailureServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderFailureService orderFailureService;

    @BeforeEach
    void setUp() {
        orderFailureService = new OrderFailureService(orderRepository);
    }

    @Test
    void markFailed_whenOrderIsAccepted_completesSuccessfully() {
        when(orderRepository.failAcceptedOrder(42L, "Execution error")).thenReturn(1);

        orderFailureService.markFailed(42L, "Execution error");

        verify(orderRepository).failAcceptedOrder(42L, "Execution error");
        verify(orderRepository, never()).findById(42L);
    }

    @Test
    void markFailed_whenOrderDoesNotExist_throwsOrderNotFoundException() {
        when(orderRepository.failAcceptedOrder(999L, "Execution error")).thenReturn(0);
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        OrderNotFoundException exception = assertThrows(
                OrderNotFoundException.class,
                () -> orderFailureService.markFailed(999L, "Execution error")
        );

        assertEquals("Order not found", exception.getMessage());
        verify(orderRepository).findById(999L);
    }

    @Test
    void markFailed_whenOrderIsNotAccepted_throwsOrderLifecycleException() {
        OrderEntity submittedOrder = new OrderEntity();
        submittedOrder.setOrderId(42L);
        submittedOrder.setStatus(OrderStatus.SUBMITTED);
        when(orderRepository.failAcceptedOrder(42L, "Execution error")).thenReturn(0);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(submittedOrder));

        OrderLifecycleException exception = assertThrows(
                OrderLifecycleException.class,
                () -> orderFailureService.markFailed(42L, "Execution error")
        );

        assertEquals(
                "Order 42 cannot transition from SUBMITTED to FAILED",
                exception.getMessage()
        );
        verify(orderRepository).findById(42L);
    }
}
