package group12.Controller;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.Services.OrderService;
import group12.dto.CreateOrderRequest;
import group12.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.math.BigDecimal;

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
    private static final BigDecimal QUANTITY = new BigDecimal("5.125");

    @Mock
    private OrderService orderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OrderController controller = new OrderController(orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(String.valueOf(CLIENT_ID))
                .claim("roles", List.of("CLIENT"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
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
        OrderEntity secondOrder = order(
                41L, CLIENT_ID, 21L, OrderType.SELL, new BigDecimal("2")
        );
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
    @DisplayName("POST /api/clients/me/orders uses the JWT client ID and returns the created order")
    void submitOrder_withValidRequest_returnsCreatedOrderAndLocation() throws Exception {
        // Arrange
        OrderEntity createdOrder = order(ORDER_ID, CLIENT_ID, INSTRUMENT_ID, OrderType.BUY, QUANTITY);
        createdOrder.setStatus(OrderStatus.ACCEPTED);
        when(orderService.submitOrder(org.mockito.ArgumentMatchers.eq(CLIENT_ID), any(CreateOrderRequest.class)))
                .thenReturn(createdOrder);

        // Act and Assert
        mockMvc.perform(post("/api/clients/me/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequestJson(INSTRUMENT_ID, "BUY", QUANTITY)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/clients/me/orders/42"))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        ArgumentCaptor<CreateOrderRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService).submitOrder(org.mockito.ArgumentMatchers.eq(CLIENT_ID), requestCaptor.capture());
        CreateOrderRequest submittedRequest = requestCaptor.getValue();
        assertEquals(INSTRUMENT_ID, submittedRequest.instrumentId());
        assertEquals(OrderType.BUY, submittedRequest.orderType());
        assertEquals(QUANTITY, submittedRequest.quantity());
    }

    @Test
    @DisplayName("POST /api/clients/me/orders returns a durable rejected order with its Location header")
    void submitOrder_whenAcceptanceRejects_returnsRejectedOrderAndLocation() throws Exception {
        OrderEntity rejectedOrder = order(
                ORDER_ID,
                CLIENT_ID,
                INSTRUMENT_ID,
                OrderType.BUY,
                QUANTITY
        );
        rejectedOrder.setStatus(OrderStatus.REJECTED);
        when(orderService.submitOrder(org.mockito.ArgumentMatchers.eq(CLIENT_ID), any(CreateOrderRequest.class)))
                .thenReturn(rejectedOrder);

        mockMvc.perform(post("/api/clients/me/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequestJson(INSTRUMENT_ID, "BUY", QUANTITY)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/clients/me/orders/42"))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verify(orderService).submitOrder(org.mockito.ArgumentMatchers.eq(CLIENT_ID), any(CreateOrderRequest.class));
    }


    @Test
        @DisplayName("POST /api/clients/me/orders with invalid order type returns 400 Bad Request")
    void submitOrder_withInvalidOrderType_returnsBadRequest() throws Exception {
        // Act and Assert
        mockMvc.perform(post("/api/clients/me/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequestJson(INSTRUMENT_ID, "PURCHASE", QUANTITY)))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).submitOrder(org.mockito.ArgumentMatchers.eq(CLIENT_ID), any(CreateOrderRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.00000001"})
        @DisplayName("POST /api/clients/me/orders rejects non-positive decimal quantities")
    void submitOrder_withNonPositiveDecimalQuantity_returnsBadRequest(String quantity)
            throws Exception {
        mockMvc.perform(post("/api/clients/me/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequestJson(
                                INSTRUMENT_ID,
                                "BUY",
                                new BigDecimal(quantity)
                        )))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).submitOrder(org.mockito.ArgumentMatchers.eq(CLIENT_ID), any(CreateOrderRequest.class));
    }


    private static String orderRequestJson(
            long instrumentId,
            String orderType,
            BigDecimal quantity
    ) {
        return """
                {
                  "instrumentId": %d,
                  "orderType": "%s",
                  "quantity": %s
                }
                """.formatted(instrumentId, orderType, quantity);
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
        order.setStatus(OrderStatus.SUBMITTED);
        return order;
    }
}
