package group12.marketdata.exception;

public class MarketDataRateLimitException extends MarketDataException {
    public MarketDataRateLimitException() {
        super("Market-data provider rate limit exceeded");
    }
}
