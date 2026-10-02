package group12.marketdata;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(AlpacaProperties.class)
// sets the configuration for alpaca 
public class AlpacaMarketDataConfiguration {

    @Bean
    @Qualifier("alpacaMarketDataRestClient")
    RestClient alpacaMarketDataRestClient(AlpacaProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        return RestClient.builder()
                .baseUrl(properties.getDataUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
