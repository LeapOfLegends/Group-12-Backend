package group12.dto;

import java.math.BigDecimal;

/**
 * Minimal account response for balance reads and money movements, avoiding
 * returning unrelated or sensitive client profile fields.
 */
public class AccountBalanceDTO {
    private final Long clientId;
    private final BigDecimal accountBalance;

    public AccountBalanceDTO(Long clientId, BigDecimal accountBalance) {
        this.clientId = clientId;
        this.accountBalance = accountBalance;
    }

    public Long getClientId() { return clientId; }
    public BigDecimal getAccountBalance() { return accountBalance; }
}
