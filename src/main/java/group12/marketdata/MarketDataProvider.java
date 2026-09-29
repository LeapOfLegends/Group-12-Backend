package group12.marketdata;

import group12.Entities.InstrumentEntity;

public interface MarketDataProvider {
    // exposes a market snapshot for a given instrument
    MarketSnapshot getCurrentMarketSnapshot(InstrumentEntity instrument);
}
