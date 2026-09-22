package group12.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

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
    private BigDecimal price;
}