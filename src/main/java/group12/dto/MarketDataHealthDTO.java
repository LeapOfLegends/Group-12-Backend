package group12.dto;

public record MarketDataHealthDTO(
        long all,
        long fresh,
        long stale,
        long noQuote,
        long notTradable
) {
}
