package group12.Controller;

import group12.dto.CreateOrderRequest;
import group12.Entities.OrderEntity;
import group12.Services.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderEntity> getOrder(
            @PathVariable Long orderId
    ) {

        OrderEntity order = orderService.getOrderById(orderId);

        return ResponseEntity.ok(order);
    }


    @GetMapping("/client/{clientId}")
    public ResponseEntity<List<OrderEntity>> getClientOrders(
            @PathVariable Long clientId
    ) {

        List<OrderEntity> orders =
                orderService.getOrdersByClientId(clientId);

        return ResponseEntity.ok(orders);
    }


    @PostMapping
    public ResponseEntity<OrderEntity> submitOrder(
            @RequestBody CreateOrderRequest request
    ) {

        OrderEntity order = orderService.submitOrder(request);

        URI location =
                URI.create("/api/orders/" + order.getOrderId());

        return ResponseEntity
                .created(location)
                .body(order);
    }
}