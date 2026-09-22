package group12.Controller;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.Services.OrderService;
import group12.dto.CreateOrderRequest;
import group12.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    private static final long ORDER_ID = 42L;
    private static final long CLIENT_ID = 10L;
    private static final long INSTRUMENT_ID = 20L;
    private static final int QUANTITY = 5;

    @Mock
    private OrderService orderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OrderController controller = new OrderController(orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/orders/{id} returns the order when it exists")
    void getOrder_whenOrderExists_returnsOrder() throws Exception {
        // Arrange
        OrderEntity order = order(ORDER_ID, CLIENT_ID, INSTRUMENT_ID, OrderType.BUY, QUANTITY);
        when(orderService.getOrderById(ORDER_ID)).thenReturn(order);

        // Act and Assert
        mockMvc.perform(get("/api/orders/{orderId}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.clientId").value(CLIENT_ID))
                .andExpect(jsonPath("$.instrumentId").value(INSTRUMENT_ID))
                .andExpect(jsonPath("$.orderType").value("BUY"))
                .andExpect(jsonPath("$.quantity").value(QUANTITY))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        verify(orderService).getOrderById(ORDER_ID);
    }

    @Test
    @DisplayName("GET /api/orders/{id} returns 404 Not Found when order does not exist")
    void getOrder_whenOrderDoesNotExist_returnsNotFoundError() throws Exception {
        // Arrange
        when(orderService.getOrderById(999L)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")
        );

        // Act and Assert
        mockMvc.perform(get("/api/orders/{orderId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Order not found"))
                .andExpect(jsonPath("$.path").value("/api/orders/999"));

        verify(orderService).getOrderById(999L);
    }

    @Test
    @DisplayName("GET /api/orders/client/{id} returns array of orders for a client")
    void getClientOrders_whenOrdersExist_returnsJsonArray() throws Exception {
        // Arrange
        OrderEntity firstOrder = order(ORDER_ID, CLIENT_ID, INSTRUMENT_ID, OrderType.BUY, QUANTITY);
        OrderEntity secondOrder = order(41L, CLIENT_ID, 21L, OrderType.SELL, 2);
        when(orderService.getOrdersByClientId(CLIENT_ID)).thenReturn(List.of(firstOrder, secondOrder));

        // Act and Assert
        mockMvc.perform(get("/api/orders/client/{clientId}", CLIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orderId").value(ORDER_ID))
                .andExpect(jsonPath("$[1].orderId").value(41));

        verify(orderService).getOrdersByClientId(CLIENT_ID);
    }

    @Test
    @DisplayName("GET /api/orders/client/{id} returns empty array when client has no orders")
    void getClientOrders_whenNoOrdersExist_returnsEmptyArray() throws Exception {
        // Arrange
        when(orderService.getOrdersByClientId(CLIENT_ID)).thenReturn(List.of());

        // Act and Assert
        mockMvc.perform(get("/api/orders/client/{clientId}", CLIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(orderService).getOrdersByClientId(CLIENT_ID);
    }

    @Test
    @DisplayName("POST /api/orders with valid request returns 201 Created with Location header and order details")
    void submitOrder_withValidRequest_returnsCreatedOrderAndLocation() throws Exception {
        // Arrange
        OrderEntity createdOrder = order(ORDER_ID, CLIENT_ID, INSTRUMENT_ID, OrderType.BUY, QUANTITY);
        when(orderService.submitOrder(any(CreateOrderRequest.class))).thenReturn(createdOrder);

        // Act and Assert
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequestJson(CLIENT_ID, INSTRUMENT_ID, "BUY", QUANTITY)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/42"))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        ArgumentCaptor<CreateOrderRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService).submitOrder(requestCaptor.capture());
        CreateOrderRequest submittedRequest = requestCaptor.getValue();
        assertEquals(CLIENT_ID, submittedRequest.clientId());
        assertEquals(INSTRUMENT_ID, submittedRequest.instrumentId());
        assertEquals(OrderType.BUY, submittedRequest.orderType());
        assertEquals(QUANTITY, submittedRequest.quantity());
    }


    @Test
    @DisplayName("POST /api/orders with invalid order type returns 400 Bad Request")
    void submitOrder_withInvalidOrderType_returnsBadRequest() throws Exception {
        // Act and Assert
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequestJson(CLIENT_ID, INSTRUMENT_ID, "PURCHASE", QUANTITY)))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).submitOrder(any(CreateOrderRequest.class));
    }


    private static String orderRequestJson(
            long clientId,
            long instrumentId,
            String orderType,
            int quantity
    ) {
        return """
                {
                  "clientId": %d,
                  "instrumentId": %d,
                  "orderType": "%s",
                  "quantity": %d
                }
                """.formatted(clientId, instrumentId, orderType, quantity);
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
        order.setStatus(OrderStatus.SUBMITTED);
        return order;
    }
}
