package group12.orderlifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "order-lifecycle.recovery")
public class OrderLifecycleRecoveryProperties {

    private Duration interval = Duration.ofSeconds(30);
    private Duration submittedAge = Duration.ofMinutes(1);

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException(
                    "order-lifecycle.recovery.interval must be positive"
            );
        }
        this.interval = interval;
    }

    public Duration getSubmittedAge() {
        return submittedAge;
    }

    public void setSubmittedAge(Duration submittedAge) {
        if (submittedAge == null || submittedAge.isZero() || submittedAge.isNegative()) {
            throw new IllegalArgumentException(
                    "order-lifecycle.recovery.submitted-age must be positive"
            );
        }
        this.submittedAge = submittedAge;
    }
}
