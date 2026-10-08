package group12.Entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MarketEntity {

    private Long marketId;
    private MarketCode marketCode;
    private MarketStatus marketStatus;
    private OffsetDateTime statusAsOf;
}
