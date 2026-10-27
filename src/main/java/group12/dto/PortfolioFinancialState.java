package group12.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record PortfolioFinancialState(
        OffsetDateTime timestamp,
        BigDecimal cashBalance,
        Map<Long, BigDecimal> instrumentQuantities
) {
    public PortfolioFinancialState {
        Objects.requireNonNull(timestamp, "timestamp is required");
        Objects.requireNonNull(cashBalance, "cashBalance is required");
        Objects.requireNonNull(instrumentQuantities, "instrumentQuantities is required");
        instrumentQuantities = Collections.unmodifiableMap(
                new LinkedHashMap<>(new TreeMap<>(instrumentQuantities))
        );
    }
}
