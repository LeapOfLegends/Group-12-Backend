package group12.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record OrderLookupDTO(
        Long orderId,
        Long clientId,
        String clientEmail,
        Long instrumentId,
        String symbol,
        String orderType,
        BigDecimal quantity,
        String status,
        BigDecimal reservedCash,
        OffsetDateTime submittedAt,
        OffsetDateTime acceptedAt,
        OffsetDateTime filledAt,
        BigDecimal executionPrice,
        BigDecimal tradeValue,
        String rejectionReason,
        String failureReason
) {
}
