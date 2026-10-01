package group12.marketdata;

import group12.Entities.InstrumentEntity;

import java.util.Collection;
import java.util.Map;

public interface MarketDataProvider {
    Map<String, MarketSnapshot> getCurrentMarketSnapshots(
            Collection<InstrumentEntity> instruments
    );
}
