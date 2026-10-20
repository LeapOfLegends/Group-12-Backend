package group12.marketdata;

import group12.Entities.MarketCode;
import group12.Entities.MarketStatus;
import group12.Services.MarketService;
import group12.marketdata.alpaca.AlpacaMarketStatusClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class MarketStatusRefreshService {

    private final MarketService marketService;
    private final AlpacaMarketStatusClient alpacaMarketStatusClient;
    private final Clock clock;

    @Autowired
    public MarketStatusRefreshService(
            MarketService marketService,
            AlpacaMarketStatusClient alpacaMarketStatusClient
    ) {
        this(marketService, alpacaMarketStatusClient, Clock.systemUTC());
    }

    MarketStatusRefreshService(
            MarketService marketService,
            AlpacaMarketStatusClient alpacaMarketStatusClient,
            Clock clock
    ) {
        this.marketService = marketService;
        this.alpacaMarketStatusClient = alpacaMarketStatusClient;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void refreshFromAlpacaClock() {
        OffsetDateTime statusAsOf = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        MarketStatus iexStatus = alpacaMarketStatusClient.isIexOpen()
                ? MarketStatus.OPEN
                : MarketStatus.CLOSED;

        marketService.updateMarketStatusSnapshot(iexStatus, MarketCode.IEX, statusAsOf);

        // Crypto is 24/7 and must remain OPEN.
        marketService.updateMarketStatusSnapshot(MarketStatus.OPEN, MarketCode.CRYPTO, statusAsOf);
    }
}