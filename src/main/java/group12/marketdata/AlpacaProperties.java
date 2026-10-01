package group12.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "alpaca")
public class AlpacaProperties {

    private String apiKey = "";
    private String secretKey = "";
    private URI dataUrl = URI.create("https://data.alpaca.markets");
    private String feed = "iex";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Set<String> supportedUsEquitySymbols = new LinkedHashSet<>();

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public URI getDataUrl() {
        return dataUrl;
    }

    public void setDataUrl(URI dataUrl) {
        this.dataUrl = dataUrl;
    }

    public String getFeed() {
        return feed;
    }

    public void setFeed(String feed) {
        this.feed = feed;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Set<String> getSupportedUsEquitySymbols() {
        return Set.copyOf(supportedUsEquitySymbols);
    }

    public void setSupportedUsEquitySymbols(Set<String> symbols) {
        supportedUsEquitySymbols = symbols == null
                ? new LinkedHashSet<>()
                : symbols.stream()
                        .filter(symbol -> symbol != null && !symbol.isBlank())
                        .map(symbol -> symbol.trim().toUpperCase(Locale.ROOT))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
