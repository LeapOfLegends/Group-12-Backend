package group12.orderlifecycle;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

@Component
public class QuoteFreshnessPolicy {

    private final OrderLifecycleProperties properties;
    private final Clock clock;

    public QuoteFreshnessPolicy(
            OrderLifecycleProperties properties,
            @Qualifier("orderLifecycleClock") Clock clock
    ) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isFresh(OffsetDateTime quoteAsOf) {
        if (quoteAsOf == null) {
            return false;
        }

        return !quoteAsOf.toInstant()
                .isBefore(clock.instant().minus(properties.getQuote().getMaxAge()));
    }
}
