package group12.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for a balance transaction. The client supplies the operation
 * type and a positive amount; the server calculates and persists the balance.
 */
public class MoneyMovementDTO {
    @NotNull(message = "Transaction type is required")
    private BalanceTransactionType type;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.00", inclusive = false, message = "Amount must be greater than zero")
    private BigDecimal amount;

    public BalanceTransactionType getType() { return type; }
    public void setType(BalanceTransactionType type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
