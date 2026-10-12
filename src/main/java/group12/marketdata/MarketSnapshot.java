package group12.marketdata;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record MarketSnapshot(
        BigDecimal bidPrice,
        BigDecimal askPrice,
        BigDecimal lastPrice,
        OffsetDateTime quoteAsOf,
        OffsetDateTime lastTradeAsOf
) {
    public MarketSnapshot {
        boolean anyQuoteValue = bidPrice != null || askPrice != null || quoteAsOf != null;
        boolean completeQuote = bidPrice != null && askPrice != null && quoteAsOf != null;
        if (anyQuoteValue && !completeQuote) {
            throw new IllegalArgumentException("Quote values must be present together");
        }

        boolean anyTradeValue = lastPrice != null || lastTradeAsOf != null;
        boolean completeTrade = lastPrice != null && lastTradeAsOf != null;
        if (anyTradeValue && !completeTrade) {
            throw new IllegalArgumentException("Last-trade values must be present together");
        }
    }

    public boolean hasQuote() {
        return bidPrice != null && askPrice != null && quoteAsOf != null;
    }

    public boolean hasLastTrade() {
        return lastPrice != null && lastTradeAsOf != null;
    }
}
