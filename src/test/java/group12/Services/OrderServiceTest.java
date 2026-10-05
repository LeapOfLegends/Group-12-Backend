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

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.util.Arrays;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderSubmissionService orderSubmissionService;
    @Mock
    private OrderAcceptanceService orderAcceptanceService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository,
                orderSubmissionService,
                orderAcceptanceService
        );
    }

    @Test
    @DisplayName("submitOrder submits first, then accepts, and returns the acceptance result")
    void submitOrder_submitsThenAcceptsAndReturnsResult() {
        CreateOrderRequest request = new CreateOrderRequest(
                10L, 20L, OrderType.BUY, new BigDecimal("7.12500000")
        );
        OrderEntity submittedOrder = order(
                42L, 10L, 20L, OrderType.BUY, new BigDecimal("7.12500000")
        );
        OrderEntity acceptedOrder = order(
                42L, 10L, 20L, OrderType.BUY, new BigDecimal("7.12500000")
        );
        acceptedOrder.setStatus(OrderStatus.ACCEPTED);
        when(orderSubmissionService.submit(request)).thenReturn(submittedOrder);
        when(orderAcceptanceService.acceptSubmittedOrder(42L)).thenReturn(acceptedOrder);

        OrderEntity result = orderService.submitOrder(request);

        var orderedCalls = inOrder(orderSubmissionService, orderAcceptanceService);
        orderedCalls.verify(orderSubmissionService).submit(request);
        orderedCalls.verify(orderAcceptanceService).acceptSubmittedOrder(42L);
        assertSame(acceptedOrder, result);
        assertEquals(OrderStatus.ACCEPTED, result.getStatus());
    }

    @Test
    @DisplayName("submitOrder has no direct execution-service dependency")
    void submitOrder_doesNotDependOnExecutionService() {
        boolean hasExecutionDependency = Arrays.stream(OrderService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(OrderExecutionService.class));

        assertFalse(hasExecutionDependency);
    }

    @Test
    @DisplayName("submitOrder propagates acceptance infrastructure failures")
    void submitOrder_whenAcceptanceFails_propagatesFailure() {
        CreateOrderRequest request = new CreateOrderRequest(
                10L, 20L, OrderType.BUY, BigDecimal.ONE
        );
        OrderEntity submittedOrder = order(42L, 10L, 20L, OrderType.BUY, BigDecimal.ONE);
        RuntimeException infrastructureFailure = new RuntimeException("database unavailable");
        when(orderSubmissionService.submit(request)).thenReturn(submittedOrder);
        when(orderAcceptanceService.acceptSubmittedOrder(42L))
                .thenThrow(infrastructureFailure);

        RuntimeException result = assertThrows(
                RuntimeException.class,
                () -> orderService.submitOrder(request)
        );

        assertSame(infrastructureFailure, result);
        verify(orderAcceptanceService).acceptSubmittedOrder(42L);
    }

    @Test
    @DisplayName("getOrderById retrieves and returns order when it exists in repository")
    void getOrderById_whenOrderExists_returnsOrder() {
        // Arrange
        OrderEntity expectedOrder = order(
                42L, 10L, 20L, OrderType.BUY, new BigDecimal("3")
        );
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
                order(42L, 10L, 20L, OrderType.BUY, new BigDecimal("3")),
                order(41L, 10L, 21L, OrderType.SELL, new BigDecimal("2"))
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
            BigDecimal quantity
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
