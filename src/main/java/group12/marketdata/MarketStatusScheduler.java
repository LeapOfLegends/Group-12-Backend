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
        prefix = "market-status.refresh",
        name = "enabled",
        havingValue = "true"
)
public class MarketStatusScheduler {

    static final Duration PROVIDER_RATE_LIMIT_COOLDOWN = Duration.ofMinutes(1);

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MarketStatusScheduler.class);

    private final MarketStatusRefreshService marketStatusRefreshService;
    private final Clock clock;
    private final AtomicBoolean cycleRunning = new AtomicBoolean();

    private volatile Instant cooldownUntil = Instant.MIN;

    @Autowired
    public MarketStatusScheduler(MarketStatusRefreshService marketStatusRefreshService) {
        this(marketStatusRefreshService, Clock.systemUTC());
    }

    MarketStatusScheduler(MarketStatusRefreshService marketStatusRefreshService, Clock clock) {
        this.marketStatusRefreshService = marketStatusRefreshService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${market-status.refresh.interval:30m}")
    public void runRefreshCycle() {
        if (!cycleRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            if (clock.instant().isBefore(cooldownUntil)) {
                return;
            }
            marketStatusRefreshService.refreshFromAlpacaClock();
        } catch (MarketDataRateLimitException exception) {
            cooldownUntil = clock.instant().plus(PROVIDER_RATE_LIMIT_COOLDOWN);
            LOGGER.warn("Market-status polling paused temporarily after provider rate limiting");
        } catch (MarketDataException exception) {
            LOGGER.warn("Scheduled market-status refresh failed: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.warn("Scheduled market-status refresh failed unexpectedly", exception);
        } finally {
            cycleRunning.set(false);
        }
    }
}