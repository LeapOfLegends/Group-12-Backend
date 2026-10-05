package group12.orderlifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "order-lifecycle")
public class OrderLifecycleProperties {

    private final Quote quote = new Quote();
    private final Execution execution = new Execution();
    private final Recovery recovery = new Recovery();

    public Quote getQuote() {
        return quote;
    }

    public Execution getExecution() {
        return execution;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public static class Quote {

        private Duration maxAge = Duration.ofSeconds(30);

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = requirePositive(
                    maxAge,
                    "order-lifecycle.quote.max-age must be positive"
            );
        }
    }

    public static class Execution {

        private Duration maxWait = Duration.ofMinutes(2);

        public Duration getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(Duration maxWait) {
            this.maxWait = requirePositive(
                    maxWait,
                    "order-lifecycle.execution.max-wait must be positive"
            );
        }
    }

    public static class Recovery {

        private Duration interval = Duration.ofSeconds(30);
        private Duration submittedAge = Duration.ofMinutes(1);

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = requirePositive(
                    interval,
                    "order-lifecycle.recovery.interval must be positive"
            );
        }

        public Duration getSubmittedAge() {
            return submittedAge;
        }

        public void setSubmittedAge(Duration submittedAge) {
            this.submittedAge = requirePositive(
                    submittedAge,
                    "order-lifecycle.recovery.submitted-age must be positive"
            );
        }
    }

    private static Duration requirePositive(Duration value, String message) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
