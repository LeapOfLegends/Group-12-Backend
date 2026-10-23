package group12.Services;

import group12.Entities.MarketCode;
import group12.Entities.MarketEntity;
import group12.Entities.MarketStatus;
import group12.Entities.OrderRejectionReason;
import group12.Repository.MarketRepository;
import group12.dto.MarketAvailabilityDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Service
public class MarketTradingService {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime CLOSE = LocalTime.of(16, 0);

    private final MarketRepository marketRepository;
    private final Clock clock;
    private final Duration maxStatusAge;

    public MarketTradingService(
            MarketRepository marketRepository,
            @Qualifier("orderLifecycleClock") Clock clock,
            @Value("${market-status.max-age:35m}") Duration maxStatusAge
    ) {
        if (maxStatusAge.isNegative() || maxStatusAge.isZero()) {
            throw new IllegalArgumentException("market-status.max-age must be positive");
        }
        this.marketRepository = marketRepository;
        this.clock = clock;
        this.maxStatusAge = maxStatusAge;
    }

    public MarketAvailabilityDTO availability(MarketCode marketCode) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (marketCode == MarketCode.CRYPTO) {
            return new MarketAvailabilityDTO(marketCode, "OPEN", true,
                    "Crypto trading is available 24/7.", now, now.plus(maxStatusAge), null);
        }
        if (marketCode == null) {
            return unavailable(null, null);
        }

        // Local hours enforce the exact regular-session boundary, even between provider polls.
        // Alpaca's clock snapshot below additionally accounts for holidays and early closures.
        var newYorkNow = now.atZoneSameInstant(NEW_YORK);
        if (marketCode == MarketCode.IEX
                && (newYorkNow.getDayOfWeek() == DayOfWeek.SATURDAY
                || newYorkNow.getDayOfWeek() == DayOfWeek.SUNDAY
                || newYorkNow.toLocalTime().isBefore(OPEN)
                || !newYorkNow.toLocalTime().isBefore(CLOSE))) {
            return new MarketAvailabilityDTO(marketCode, "CLOSED", false,
                    "IEX market is closed. Regular hours are Monday–Friday, 9:30 AM–4:00 PM America/New_York, excluding market holidays.",
                    now, null, OrderRejectionReason.MARKET_CLOSED);
        }

        MarketEntity market = marketRepository.getMarketEntityForUpdate(marketCode);
        if (market == null || market.getMarketStatus() == null || market.getStatusAsOf() == null
                || market.getStatusAsOf().isAfter(now)
                || !now.isBefore(market.getStatusAsOf().plus(maxStatusAge))) {
            return unavailable(marketCode, market == null ? null : market.getStatusAsOf());
        }
        if (market.getMarketStatus() == MarketStatus.CLOSED) {
            return new MarketAvailabilityDTO(marketCode, "CLOSED", false,
                    marketCode + " market is closed. You can place an order when it reopens.",
                    market.getStatusAsOf(), null, OrderRejectionReason.MARKET_CLOSED);
        }

        OffsetDateTime validUntil = market.getStatusAsOf().plus(maxStatusAge);
        if (marketCode == MarketCode.IEX) {
            OffsetDateTime sessionClose = newYorkNow.with(CLOSE).toOffsetDateTime();
            if (sessionClose.isBefore(validUntil)) {
                validUntil = sessionClose;
            }
        }
        return new MarketAvailabilityDTO(marketCode, "OPEN", true,
                marketCode + " market is open.", market.getStatusAsOf(), validUntil, null);
    }

    private MarketAvailabilityDTO unavailable(MarketCode marketCode, OffsetDateTime statusAsOf) {
        return new MarketAvailabilityDTO(marketCode, "UNAVAILABLE", false,
                "Market status is unavailable or out of date. Trading is disabled until it can be verified.",
                statusAsOf, null, OrderRejectionReason.MARKET_STATUS_UNAVAILABLE);
    }
}