package group12.dto;

import java.math.BigDecimal;

public record AssetAllocationDTO(
        String assetClass,
        BigDecimal marketValue,
        BigDecimal allocationPercent
) {
}
