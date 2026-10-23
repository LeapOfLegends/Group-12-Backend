package group12.marketdata;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class InstrumentHistoryConfiguration {

    @Bean
    Clock instrumentHistoryClock() {
        return Clock.systemUTC();
    }
}
