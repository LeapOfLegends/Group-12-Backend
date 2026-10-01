package group12.marketdata;


//  provider-neutral attributes needed to identify and determine support for an instrument
public record MarketDataRequest(
        String symbol,
        String assetClass,
        String currency
) {
}
