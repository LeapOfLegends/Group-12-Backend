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
    private final List<MarketDataProvider> marketDataProviders;
    private final MarketSnapshotPersistenceService persistenceService;

    public MarketDataBatchRefreshService(
            InstrumentRepository instrumentRepository,
            List<MarketDataProvider> marketDataProviders,
            MarketSnapshotPersistenceService persistenceService
    ) {
        this.instrumentRepository = instrumentRepository;
        this.marketDataProviders = List.copyOf(marketDataProviders);
        this.persistenceService = persistenceService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int refreshEligibleInstruments() {

        List<InstrumentRequest> eligibleInstruments = new ArrayList<>();

        for (InstrumentEntity instrument : instrumentRepository.findTradableInstruments()) {
            if (instrument == null || !instrument.isTradable()) {
                continue;
            }

            MarketDataRequest request = toMarketDataRequest(instrument);
            eligibleInstruments.add(new InstrumentRequest(
                    instrument,
                    request
            ));
        }

        int persistedInstruments = 0;
        for (MarketDataProvider marketDataProvider : marketDataProviders) {
            Map<String, List<InstrumentEntity>> providerInstrumentsBySymbol = new TreeMap<>();
            Map<String, MarketDataRequest> providerRequestsBySymbol = new TreeMap<>();
            for (InstrumentRequest eligibleInstrument : eligibleInstruments) {
                MarketDataRequest request = eligibleInstrument.request();
                if (!marketDataProvider.supports(request)) {
                    continue;
                }
                String symbol = normalizeSymbol(request.symbol());
                providerInstrumentsBySymbol
                        .computeIfAbsent(symbol, ignored -> new ArrayList<>())
                        .add(eligibleInstrument.instrument());
                providerRequestsBySymbol.putIfAbsent(symbol, request);
            }
            if (providerRequestsBySymbol.isEmpty()) {
                continue;
            }

            Map<String, MarketSnapshot> snapshots =
                    marketDataProvider.getCurrentMarketSnapshots(providerRequestsBySymbol.values());
            for (Map.Entry<String, MarketSnapshot> entry : snapshots.entrySet()) {
                List<InstrumentEntity> matchingInstruments =
                        providerInstrumentsBySymbol.get(normalizeSymbol(entry.getKey()));
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
        }
        return persistedInstruments;
    }

    private MarketDataRequest toMarketDataRequest(InstrumentEntity instrument) {
        return new MarketDataRequest(
                instrument.getSymbol(),
                instrument.getAssetClass(),
                instrument.getCurrency(),
                instrument.getMarketCode()
        );
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
    private record InstrumentRequest(
            InstrumentEntity instrument,
            MarketDataRequest request
    ) {
    }
}
