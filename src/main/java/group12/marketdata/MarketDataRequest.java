package group12.marketdata;


import group12.Entities.MarketCode;

//  provider-neutral attributes needed to identify and determine support for an instrument
public record MarketDataRequest(
        String symbol,
        String assetClass,
        String currency,
        MarketCode marketCode
) {
}
