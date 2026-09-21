package group12.Entities;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class OrderEntity {

    private Long orderId;
    private Long clientId;
    private Long instrumentId;
    private OrderType orderType;
    private Integer quantity;
    private OrderStatus status;
    private OffsetDateTime submittedAt;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime rejectedAt;
    private OffsetDateTime failedAt;
    private OffsetDateTime filledAt;
    private BigDecimal executionPrice;
    private BigDecimal tradeValue;
    private String rejectionReason;
    private String failureReason;
}
