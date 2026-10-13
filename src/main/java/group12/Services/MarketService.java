package group12.Services;

import org.springframework.stereotype.Service;

import group12.Entities.MarketCode;
import group12.Entities.MarketEntity;
import group12.Entities.MarketStatus;
import group12.Repository.MarketRepository;

import group12.exception.MarketCannotUpdateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;

@Slf4j 
@Service
@RequiredArgsConstructor

public class MarketService {

    private final MarketRepository marketRepository; 


    public MarketStatus getMarketStatusByMarketCode(MarketCode marketCode) {

        MarketStatus marketStatus = marketRepository.getMarketStatusByMarketCode(marketCode);

        return marketStatus;
    }

    public int updateMarketStatusSnapshot(MarketStatus marketStatus, MarketCode marketCode) {
        return updateMarketStatusSnapshot(
                marketStatus,
                marketCode,
                OffsetDateTime.now()
        );
    }

    public int updateMarketStatusSnapshot(
            MarketStatus marketStatus,
            MarketCode marketCode,
            OffsetDateTime statusAsOf
    ) {
        MarketEntity market = marketRepository.getMarketEntityForUpdate(marketCode);
        if (market == null) {
            throw new MarketCannotUpdateException("Market cannot be updated");
        }

        int updatedRows = marketRepository.updateMarketStatusSnapshot(
                marketStatus,
                marketCode,
                statusAsOf
        );
        if (updatedRows == 0) {
            throw new MarketCannotUpdateException("Market cannot be updated");
        }

        market.setMarketStatus(marketStatus);
        market.setStatusAsOf(statusAsOf);
        log.info("Market updated: marketStatus={}, marketCode={}", marketStatus, marketCode);
        return updatedRows;

    }
    
}
