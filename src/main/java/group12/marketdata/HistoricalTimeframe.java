package group12.marketdata;

public enum HistoricalTimeframe {
    FIVE_MINUTES("5Min"),
    THIRTY_MINUTES("30Min"),
    ONE_DAY("1Day"),
    ONE_WEEK("1Week"),
    ONE_MONTH("1Month");

    private final String databaseValue;

    HistoricalTimeframe(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String getDatabaseValue() {
        return databaseValue;
    }
}
