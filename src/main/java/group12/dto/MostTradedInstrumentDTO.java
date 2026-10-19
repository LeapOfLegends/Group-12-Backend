package group12.dto;

public record MostTradedInstrumentDTO(
        Long instrumentId,
        String symbol,
        Long tradedOrders
) {
}
