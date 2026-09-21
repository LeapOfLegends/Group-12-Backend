package group12.Entities;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter 
@Setter 
@NoArgsConstructor 
public class HoldingEntity {

    private Long holdingId;
    private Long clientId;
    private Long instrumentId;
    private Integer quantity;
    private BigDecimal averageCost;
    private OffsetDateTime updatedAt;
}
