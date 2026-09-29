package group12.marketdata;

import group12.marketdata.exception.MarketDataException;
import group12.marketdata.exception.MarketDataRateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        prefix = "market-data.refresh",
        name = "enabled",
        havingValue = "true"
)
public class MarketDataBatchScheduler {

    static final Duration RATE_LIMIT_COOLDOWN = Duration.ofMinutes(1);

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MarketDataBatchScheduler.class);

    private final MarketDataBatchRefreshService batchRefreshService;
    private final Clock clock;
    private final AtomicBoolean cycleRunning = new AtomicBoolean();

    private volatile Instant cooldownUntil = Instant.MIN;

    @Autowired
    public MarketDataBatchScheduler(MarketDataBatchRefreshService batchRefreshService) {
        this(batchRefreshService, Clock.systemUTC());
    }

    MarketDataBatchScheduler(MarketDataBatchRefreshService batchRefreshService, Clock clock) {
        this.batchRefreshService = batchRefreshService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${market-data.refresh.interval:5s}")
    public void runRefreshCycle() {
        if (!cycleRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            if (clock.instant().isBefore(cooldownUntil)) {
                return;
            }
            batchRefreshService.refreshEligibleInstruments();
        } catch (MarketDataRateLimitException exception) {
            cooldownUntil = clock.instant().plus(RATE_LIMIT_COOLDOWN);
            LOGGER.warn("Market-data polling paused temporarily after provider rate limiting");
        } catch (MarketDataException exception) {
            LOGGER.warn("Scheduled market-data refresh failed: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.warn("Scheduled market-data refresh failed unexpectedly");
        } finally {
            cycleRunning.set(false);
        }
    }
}
