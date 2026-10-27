package group12.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

public record PortfolioFinancialEvent(
        Long transactionId,
        OffsetDateTime timestamp,
        String transactionType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Long orderId,
        Long instrumentId,
        BigDecimal quantityDelta
) {
    public PortfolioFinancialEvent {
        Objects.requireNonNull(transactionId, "transactionId is required");
        Objects.requireNonNull(timestamp, "timestamp is required");
        Objects.requireNonNull(transactionType, "transactionType is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(balanceAfter, "balanceAfter is required");
    }
}
