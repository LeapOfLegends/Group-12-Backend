package group12.Services;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.Repository.OrderRepository;
import group12.dto.CreateOrderRequest;
import group12.exception.OrderSubmissionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderSubmissionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new OrderSubmissionService(orderRepository);
    }

    @Test
    void submit_buildsInsertsAndReloadsSubmittedOrder() {
        CreateOrderRequest request = new CreateOrderRequest(
                10L, 20L, OrderType.BUY, new BigDecimal("7.12500000")
        );
        OrderEntity persisted = new OrderEntity();
        persisted.setOrderId(42L);
        persisted.setStatus(OrderStatus.SUBMITTED);

        doAnswer(invocation -> {
            OrderEntity inserted = invocation.getArgument(0);
            inserted.setOrderId(42L);
            return 1;
        }).when(orderRepository).insert(any(OrderEntity.class));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(persisted));

        OrderEntity result = service.submit(request);

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderRepository).insert(captor.capture());
        assertEquals(10L, captor.getValue().getClientId());
        assertEquals(20L, captor.getValue().getInstrumentId());
        assertEquals(OrderType.BUY, captor.getValue().getOrderType());
        assertEquals(new BigDecimal("7.12500000"), captor.getValue().getQuantity());
        verify(orderRepository).findById(42L);
        assertSame(persisted, result);
    }

    @Test
    void submit_whenInsertDoesNotAffectExactlyOneRow_throws() {
        CreateOrderRequest request = new CreateOrderRequest(
                10L, 20L, OrderType.BUY, BigDecimal.ONE
        );
        when(orderRepository.insert(any(OrderEntity.class))).thenReturn(0);

        OrderSubmissionException exception = assertThrows(
                OrderSubmissionException.class,
                () -> service.submit(request)
        );

        assertEquals("Expected to insert one order but inserted 0", exception.getMessage());
    }

    @Test
    void submit_whenInsertedOrderCannotBeReloaded_throws() {
        CreateOrderRequest request = new CreateOrderRequest(
                10L, 20L, OrderType.BUY, BigDecimal.ONE
        );
        doAnswer(invocation -> {
            invocation.<OrderEntity>getArgument(0).setOrderId(42L);
            return 1;
        }).when(orderRepository).insert(any(OrderEntity.class));
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(OrderSubmissionException.class, () -> service.submit(request));
    }
}
