package group12.marketdata;

import group12.Entities.MarketCode;

public record MarketDataRequest(
        String symbol,
        String assetClass,
        String currency,
        MarketCode marketCode
) {
}
