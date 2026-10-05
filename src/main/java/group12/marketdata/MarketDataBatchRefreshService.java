package group12.marketdata;

import group12.Entities.InstrumentEntity;
import group12.Repository.InstrumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class MarketDataBatchRefreshService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MarketDataBatchRefreshService.class);

    private final InstrumentRepository instrumentRepository;
    private final MarketDataProvider marketDataProvider;
    private final MarketSnapshotPersistenceService persistenceService;

    public MarketDataBatchRefreshService(
            InstrumentRepository instrumentRepository,
            MarketDataProvider marketDataProvider,
            MarketSnapshotPersistenceService persistenceService
    ) {
        this.instrumentRepository = instrumentRepository;
        this.marketDataProvider = marketDataProvider;
        this.persistenceService = persistenceService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int refreshEligibleInstruments() {
        Map<String, List<InstrumentEntity>> instrumentsBySymbol = new TreeMap<>();
        Map<String, MarketDataRequest> requestsBySymbol = new TreeMap<>();
        for (InstrumentEntity instrument : instrumentRepository.findTradableInstruments()) {
            if (instrument == null || !instrument.isTradable()) {
                continue;
            }

            MarketDataRequest request = toMarketDataRequest(instrument);
            if (!marketDataProvider.supports(request)) {
                continue;
            }

            String symbol = normalizeSymbol(request.symbol());
            instrumentsBySymbol
                    .computeIfAbsent(symbol, ignored -> new ArrayList<>())
                    .add(instrument);
            requestsBySymbol.putIfAbsent(symbol, request);
        }
        if (instrumentsBySymbol.isEmpty()) {
            return 0;
        }

        Map<String, MarketSnapshot> snapshots =
                marketDataProvider.getCurrentMarketSnapshots(requestsBySymbol.values());

        int persistedInstruments = 0;
        for (Map.Entry<String, MarketSnapshot> entry : snapshots.entrySet()) {
            List<InstrumentEntity> matchingInstruments =
                    instrumentsBySymbol.get(normalizeSymbol(entry.getKey()));
            if (matchingInstruments == null) {
                continue;
            }
            for (InstrumentEntity instrument : matchingInstruments) {
                try {
                    if (persistenceService.persist(
                            instrument.getInstrumentId(),
                            entry.getValue()
                    )) {
                        persistedInstruments++;
                    }
                } catch (RuntimeException exception) {
                    LOGGER.warn(
                            "Could not persist market snapshot for instrument {} ({})",
                            instrument.getInstrumentId(),
                            normalizeSymbol(instrument.getSymbol()),
                            exception
                    );
                }
            }
        }
        return persistedInstruments;
    }

    private MarketDataRequest toMarketDataRequest(InstrumentEntity instrument) {
        return new MarketDataRequest(
                instrument.getSymbol(),
                instrument.getAssetClass(),
                instrument.getCurrency()
        );
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
