package group12.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    private Long orderId;
    private Long clientId;
    private Long instrumentId;
    private String orderType;      // BUY, SELL
    private Double quantity;
    private LocalDateTime createdAt;
}
