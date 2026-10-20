package group12.Entities;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TransactionsEntity {

    private Long transactionId;
    private Long clientId;
    private Long orderId;
    private String transactionType;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private OffsetDateTime createdAt;
    
}
