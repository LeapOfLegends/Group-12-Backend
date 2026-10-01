package group12.marketdata;

import java.util.Collection;
import java.util.Map;

public interface MarketDataProvider {

    boolean supports(MarketDataRequest instrument);

    Map<String, MarketSnapshot> getCurrentMarketSnapshots(
            Collection<MarketDataRequest> instruments
    );
}
