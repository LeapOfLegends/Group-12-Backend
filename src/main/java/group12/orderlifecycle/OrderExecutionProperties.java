package group12.orderlifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "order-lifecycle.execution")
public class OrderExecutionProperties {

    private Duration maxWait = Duration.ofMinutes(2);

    public Duration getMaxWait() {
        return maxWait;
    }

    public void setMaxWait(Duration maxWait) {
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            throw new IllegalArgumentException(
                    "order-lifecycle.execution.max-wait must be positive"
            );
        }
        this.maxWait = maxWait;
    }
}
