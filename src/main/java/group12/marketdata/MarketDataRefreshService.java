package group12.marketdata;

import group12.Entities.InstrumentEntity;
import group12.Repository.InstrumentRepository;
import group12.marketdata.exception.MarketDataInstrumentNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketDataRefreshService {

    private final InstrumentRepository instrumentRepository;
    private final MarketDataProvider marketDataProvider;
    private final MarketSnapshotPersistenceService persistenceService;

    public MarketDataRefreshService(
            InstrumentRepository instrumentRepository,
            MarketDataProvider marketDataProvider,
            MarketSnapshotPersistenceService persistenceService
    ) {
        this.instrumentRepository = instrumentRepository;
        this.marketDataProvider = marketDataProvider;
        this.persistenceService = persistenceService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MarketSnapshot refreshInstrument(Long instrumentId) {
        InstrumentEntity instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new MarketDataInstrumentNotFoundException(instrumentId));

        MarketSnapshot snapshot = marketDataProvider.getCurrentMarketSnapshot(instrument);
        persistenceService.persist(instrumentId, snapshot);
        return snapshot;
    }
}
