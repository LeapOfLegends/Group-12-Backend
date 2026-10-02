package group12.orderlifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "order-lifecycle.quote")
public class QuoteFreshnessProperties {

    private Duration maxAge = Duration.ofSeconds(30);

    public Duration getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Duration maxAge) {
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("order-lifecycle.quote.max-age must be positive");
        }
        this.maxAge = maxAge;
    }
}
