package group12.marketdata.exception;

public class MarketDataInstrumentNotFoundException extends MarketDataException {
    public MarketDataInstrumentNotFoundException(Long instrumentId) {
        super("Instrument not found: " + instrumentId);
    }
}
