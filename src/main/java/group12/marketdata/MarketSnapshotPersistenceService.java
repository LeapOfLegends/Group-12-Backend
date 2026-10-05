package group12.marketdata;

import group12.Repository.InstrumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
// service for writing market snapshots to the database
public class MarketSnapshotPersistenceService {

    private final InstrumentRepository instrumentRepository;

    public MarketSnapshotPersistenceService(InstrumentRepository instrumentRepository) {
        this.instrumentRepository = instrumentRepository;
    }

    @Transactional
    public boolean persist(Long instrumentId, MarketSnapshot snapshot) {
        boolean observationWritten = false;
        if (snapshot.hasQuote()) {
            observationWritten = instrumentRepository.updateQuoteSnapshot(
                    instrumentId,
                    snapshot.bidPrice(),
                    snapshot.askPrice(),
                    snapshot.quoteAsOf()
            ) > 0;
        }
        if (snapshot.hasLastTrade()) {
            boolean tradeWritten = instrumentRepository.updateLastTradeSnapshot(
                    instrumentId,
                    snapshot.lastPrice(),
                    snapshot.lastTradeAsOf()
            ) > 0;
            observationWritten = observationWritten || tradeWritten;
        }
        return observationWritten;
    }
}
