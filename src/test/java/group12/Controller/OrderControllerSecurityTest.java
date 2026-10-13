package group12.Controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import group12.Entities.OrderEntity;
import group12.Entities.OrderType;
import group12.Services.OrderService;
import group12.dto.CreateOrderRequest;
import group12.exception.OrderNotFoundException;
import group12.exception.GlobalExceptionHandler;
import group12.security.JwtConfiguration;
import group12.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@ContextConfiguration(classes = OrderControllerSecurityTest.SecurityTestConfiguration.class)
@TestPropertySource(properties = "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
class OrderControllerSecurityTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Configuration(proxyBeanMethods = false)
    @Import({OrderController.class, GlobalExceptionHandler.class, SecurityConfig.class, JwtConfiguration.class})
    static class SecurityTestConfiguration {
    }

    @Test
    void clientOrderRoutesUseJwtSubjectAndNestedClientPaths() throws Exception {
        when(orderService.getOrdersByClientId(123L)).thenReturn(List.of());
        OrderEntity order = new OrderEntity();
        order.setOrderId(42L);
        order.setClientId(123L);
        order.setInstrumentId(7L);
        order.setOrderType(OrderType.BUY);
        when(orderService.getOrderByIdAndClientId(42L, 123L)).thenReturn(order);
        String token = token("123", "CLIENT");

        mockMvc.perform(get("/api/clients/me/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/clients/me/orders/42").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(orderService).getOrdersByClientId(123L);
        verify(orderService).getOrderByIdAndClientId(42L, 123L);
    }

    @Test
    void clientCannotRetrieveOrderOutsideTheirAccount() throws Exception {
        when(orderService.getOrderByIdAndClientId(99L, 123L))
                .thenThrow(new OrderNotFoundException("Order not found"));

        mockMvc.perform(get("/api/clients/me/orders/99")
                        .header("Authorization", "Bearer " + token("123", "CLIENT")))
                .andExpect(status().isNotFound());

        verify(orderService).getOrderByIdAndClientId(99L, 123L);
    }

    @Test
    void clientCanSubmitOrderAndServerUsesJwtSubject() throws Exception {
        OrderEntity createdOrder = new OrderEntity();
        createdOrder.setOrderId(42L);
        createdOrder.setClientId(123L);
        when(orderService.submitOrder(eq(123L), any(CreateOrderRequest.class))).thenReturn(createdOrder);

        mockMvc.perform(post("/api/clients/me/orders")
                        .header("Authorization", "Bearer " + token("123", "CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":7,"orderType":"BUY","quantity":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/clients/me/orders/42"));

        verify(orderService).submitOrder(eq(123L), any(CreateOrderRequest.class));
    }

    @Test
    void clientCannotUseAdminOrderReadRoutes() throws Exception {
        String token = token("123", "CLIENT");

        mockMvc.perform(get("/api/orders/42").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders/client/456").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPER_ADMIN", "FINANCE", "OPERATIONS"})
    void eachAdminRoleCanReadOrdersButCannotUseClientSelfServiceRoutes(String role) throws Exception {
        when(orderService.getOrderById(42L)).thenReturn(new OrderEntity());
        when(orderService.getOrdersByClientId(456L)).thenReturn(List.of());
        String token = token("789", role);

        mockMvc.perform(get("/api/orders/42").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/orders/client/456").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/clients/me/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/clients/me/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instrumentId\":7,\"orderType\":\"BUY\",\"quantity\":2}"))
                .andExpect(status().isForbidden());

        verify(orderService).getOrderById(42L);
        verify(orderService).getOrdersByClientId(456L);
    }

    @Test
    void oldClientOrderPathsAreNotMapped() throws Exception {
        String token = token("123", "CLIENT");

        mockMvc.perform(get("/api/orders/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/orders/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void clientOrderRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/clients/me/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedTokenWithoutRoleCannotAccessClientOrderRoutes() throws Exception {
        mockMvc.perform(get("/api/clients/me/orders")
                        .header("Authorization", "Bearer " + tokenWithoutRoles("123")))
                .andExpect(status().isForbidden());
    }

    private String token(String subject, String role) throws JOSEException {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"" + subject + "\",\"roles\":[\"" + role + "\"],"
                + "\"iat\":" + issuedAt + ",\"exp\":" + (issuedAt + 300) + "}";
        return signedToken(payload);
    }

    private String tokenWithoutRoles(String subject) throws JOSEException {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"" + subject + "\",\"iat\":" + issuedAt
                + ",\"exp\":" + (issuedAt + 300) + "}";
        return signedToken(payload);
    }

    private String signedToken(String payload) throws JOSEException {
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(payload));
        jws.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jws.serialize();
    }
}
