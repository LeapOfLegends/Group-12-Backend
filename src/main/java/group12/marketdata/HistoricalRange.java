package group12.marketdata;

public enum HistoricalRange {
    ONE_DAY("1D", HistoricalTimeframe.FIVE_MINUTES),
    FIVE_DAYS("5D", HistoricalTimeframe.THIRTY_MINUTES),
    ONE_MONTH("1M", HistoricalTimeframe.ONE_DAY),
    ONE_YEAR("1Y", HistoricalTimeframe.ONE_WEEK),
    FIVE_YEARS("5Y", HistoricalTimeframe.ONE_MONTH);

    private final String apiValue;
    private final HistoricalTimeframe timeframe;

    HistoricalRange(String apiValue, HistoricalTimeframe timeframe) {
        this.apiValue = apiValue;
        this.timeframe = timeframe;
    }

    public String getApiValue() {
        return apiValue;
    }

    public HistoricalTimeframe getTimeframe() {
        return timeframe;
    }

    public boolean isIntradayEquityRange() {
        return this == ONE_DAY || this == FIVE_DAYS;
    }

    public int representedSessionCount() {
        return this == ONE_DAY ? 1 : this == FIVE_DAYS ? 5 : 0;
    }

    public static HistoricalRange fromApiValue(String value) {
        if (value != null) {
            for (HistoricalRange range : values()) {
                if (range.apiValue.equals(value)) {
                    return range;
                }
            }
        }
        throw new IllegalArgumentException(
                "Invalid range. Allowed values: 1D, 5D, 1M, 1Y, 5Y"
        );
    }
}
