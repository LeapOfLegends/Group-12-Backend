package group12.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record PortfolioPerformanceDTO(
        String range,
        String timeframe,
        String currency,
        OffsetDateTime availableFrom,
        OffsetDateTime availableTo,
        boolean partial,
        String returnMethod,
        int unavailablePointCount,
        List<String> limitations,
        List<PortfolioPerformancePointDTO> points
) {
    public PortfolioPerformanceDTO {
        limitations = List.copyOf(limitations);
        points = List.copyOf(points);
    }
}
