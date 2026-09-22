package group12.Services;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.Repository.OrderRepository;
import group12.dto.CreateOrderRequest;
import group12.exception.OrderNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.util.List;
import java.util.Optional;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository);
    }

    @Test
    @DisplayName("submitOrder inserts new order with correct details and returns persisted order from repository")
    void submitOrder_withValidRequest_insertsAndReturnsPersistedOrder() {
        // Arrange
        CreateOrderRequest request = new CreateOrderRequest(10L, 20L, OrderType.BUY, 7);
        OrderEntity persistedOrder = order(42L, 10L, 20L, OrderType.BUY, 7);
        persistedOrder.setStatus(OrderStatus.SUBMITTED);

        doAnswer(invocation -> {
            OrderEntity insertedOrder = invocation.getArgument(0);
            insertedOrder.setOrderId(42L);
            return 1;
        }).when(orderRepository).insert(any(OrderEntity.class));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(persistedOrder));

        // Act
        OrderEntity result = orderService.submitOrder(request);

        // Assert
        ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderRepository).insert(orderCaptor.capture());
        OrderEntity insertedOrder = orderCaptor.getValue();
        assertEquals(10L, insertedOrder.getClientId());
        assertEquals(20L, insertedOrder.getInstrumentId());
        assertEquals(OrderType.BUY, insertedOrder.getOrderType());
        assertEquals(7, insertedOrder.getQuantity());
        verify(orderRepository).findById(42L);
        assertSame(persistedOrder, result);
    }

    @Test
    @DisplayName("getOrderById retrieves and returns order when it exists in repository")
    void getOrderById_whenOrderExists_returnsOrder() {
        // Arrange
        OrderEntity expectedOrder = order(42L, 10L, 20L, OrderType.BUY, 3);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(expectedOrder));

        // Act
        OrderEntity result = orderService.getOrderById(42L);

        // Assert
        assertSame(expectedOrder, result);
        verify(orderRepository).findById(42L);
    }

    @Test
    @DisplayName("getOrderById throws OrderNotFoundException with message when order does not exist")
    void getOrderById_whenOrderDoesNotExist_throwsNotFoundException() {
        // Arrange
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        // Act
        OrderNotFoundException exception = assertThrows(
                OrderNotFoundException.class,
                () -> orderService.getOrderById(999L)
        );
        assertEquals("Order not found", exception.getMessage());
        verify(orderRepository).findById(999L);
    }

    @Test
    @DisplayName("getOrdersByClientId returns list of orders from repository for given client ID")
    void getOrdersByClientId_returnsRepositoryResults() {
        // Arrange
        List<OrderEntity> expectedOrders = List.of(
                order(42L, 10L, 20L, OrderType.BUY, 3),
                order(41L, 10L, 21L, OrderType.SELL, 2)
        );
        when(orderRepository.findByClientId(10L)).thenReturn(expectedOrders);

        // Act
        List<OrderEntity> result = orderService.getOrdersByClientId(10L);

        // Assert
        assertSame(expectedOrders, result);
        verify(orderRepository).findByClientId(10L);
    }


    private static OrderEntity order(
            Long orderId,
            Long clientId,
            Long instrumentId,
            OrderType orderType,
            Integer quantity
    ) {
        OrderEntity order = new OrderEntity();
        order.setOrderId(orderId);
        order.setClientId(clientId);
        order.setInstrumentId(instrumentId);
        order.setOrderType(orderType);
        order.setQuantity(quantity);
        return order;
    }
}
