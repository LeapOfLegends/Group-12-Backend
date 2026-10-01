package group12.marketdata;

import group12.Entities.InstrumentEntity;
import group12.Repository.InstrumentRepository;
import group12.marketdata.exception.MarketDataConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class MarketDataBatchRefreshService {

    static final int MAX_BATCH_SYMBOLS = 50;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MarketDataBatchRefreshService.class);

    private final InstrumentRepository instrumentRepository;
    private final MarketDataProvider marketDataProvider;
    private final MarketSnapshotPersistenceService persistenceService;
    private final AlpacaProperties alpacaProperties;

    public MarketDataBatchRefreshService(
            InstrumentRepository instrumentRepository,
            MarketDataProvider marketDataProvider,
            MarketSnapshotPersistenceService persistenceService,
            AlpacaProperties alpacaProperties
    ) {
        this.instrumentRepository = instrumentRepository;
        this.marketDataProvider = marketDataProvider;
        this.persistenceService = persistenceService;
        this.alpacaProperties = alpacaProperties;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int refreshEligibleInstruments() {
        Set<String> allowlist = alpacaProperties.getSupportedUsEquitySymbols();
        if (allowlist.size() > MAX_BATCH_SYMBOLS) {
            throw new MarketDataConfigurationException(
                    "At most 50 US equity symbols may be configured for batch refresh"
            );
        }

        Map<String, List<InstrumentEntity>> instrumentsBySymbol = new TreeMap<>();
        for (InstrumentEntity instrument : instrumentRepository.findTradableUsdEquities()) {
            if (!isEligible(instrument, allowlist)) {
                continue;
            }
            String symbol = normalizeSymbol(instrument.getSymbol());
            instrumentsBySymbol
                    .computeIfAbsent(symbol, ignored -> new ArrayList<>())
                    .add(instrument);
        }

        if (instrumentsBySymbol.size() > MAX_BATCH_SYMBOLS) {
            throw new MarketDataConfigurationException(
                    "More than 50 eligible US equity symbols were found for batch refresh"
            );
        }
        if (instrumentsBySymbol.isEmpty()) {
            return 0;
        }

        List<InstrumentEntity> uniqueInstruments = instrumentsBySymbol.values().stream()
                .map(List::getFirst)
                .toList();
        Map<String, MarketSnapshot> snapshots =
                marketDataProvider.getCurrentMarketSnapshots(uniqueInstruments);

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

    private boolean isEligible(InstrumentEntity instrument, Set<String> allowlist) {
        if (instrument == null || instrument.getSymbol() == null) {
            return false;
        }
        return instrument.isTradable()
                && "Equity".equalsIgnoreCase(instrument.getAssetClass())
                && "USD".equalsIgnoreCase(instrument.getCurrency())
                && allowlist.contains(normalizeSymbol(instrument.getSymbol()));
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
