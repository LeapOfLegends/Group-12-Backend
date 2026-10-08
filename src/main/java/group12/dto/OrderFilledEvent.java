package group12.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor

public class OrderFilledEvent {
    private Long orderId;
    private Long clientId;
    private Double quantityFilled;
    private BigDecimal executionPrice;
    private LocalDateTime filledAt;
}

