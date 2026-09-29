package group12.marketdata;

import group12.Entities.InstrumentEntity;

import java.util.Collection;
import java.util.Map;

public interface MarketDataProvider {
    // exposes a market snapshot for a given instrument
    MarketSnapshot getCurrentMarketSnapshot(InstrumentEntity instrument);

    Map<String, MarketSnapshot> getCurrentMarketSnapshots(
            Collection<InstrumentEntity> instruments
    );
}
