package group12.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentDTO {

    private String symbol;
    private String instrumentName;
    private String assetClass;
    private String currency;
    private boolean tradable;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;
    private BigDecimal lastPrice;
    private OffsetDateTime quoteAsOf;
    private OffsetDateTime lastTradeAsOf;
}
