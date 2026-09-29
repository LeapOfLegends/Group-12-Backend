package group12.marketdata.exception;

public class MarketDataAuthenticationException extends MarketDataException {
    public MarketDataAuthenticationException() {
        super("Market-data provider authentication or authorization failed");
    }
}
