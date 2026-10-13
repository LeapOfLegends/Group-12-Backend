package group12.Controller;

import group12.dto.CreateOrderRequest;
import group12.Entities.OrderEntity;
import group12.Services.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /*
     * =========================================================================
     * CLIENT SELF-SERVICE ENDPOINTS
     *
     * The client ID is taken from the verified JWT subject so clients can only
     * read their own orders or submit orders for their own account.
     * =========================================================================
     */
    @GetMapping("/api/clients/me/orders")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<List<OrderEntity>> getCurrentClientOrders(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(orderService.getOrdersByClientId(currentClientId(jwt)));
    }

    @GetMapping("/api/clients/me/orders/{orderId}")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<OrderEntity> getCurrentClientOrder(
            @PathVariable Long orderId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(orderService.getOrderByIdAndClientId(orderId, currentClientId(jwt)));
    }

    @PostMapping("/api/clients/me/orders")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<OrderEntity> submitCurrentClientOrder(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        OrderEntity order = orderService.submitOrder(currentClientId(jwt), request);

        URI location = URI.create("/api/clients/me/orders/" + order.getOrderId());

        return ResponseEntity
                .created(location)
                .body(order);
    }

    /*
     * =========================================================================
     * ADMIN ORDER READ ENDPOINTS
     *
     * Finance, Operations, and Super Admin may review orders for client support
     * and account operations. Order submission remains a client-only action.
     * =========================================================================
     */
    @GetMapping("/api/orders/{orderId:\\d+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public ResponseEntity<OrderEntity> getOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @GetMapping("/api/orders/client/{clientId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public ResponseEntity<List<OrderEntity>> getClientOrders(@PathVariable Long clientId) {
        return ResponseEntity.ok(orderService.getOrdersByClientId(clientId));
    }

    private Long currentClientId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
