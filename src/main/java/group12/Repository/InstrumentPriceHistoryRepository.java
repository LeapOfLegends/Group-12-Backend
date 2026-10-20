package group12.Repository;

import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalTimeframe;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@Mapper
public interface InstrumentPriceHistoryRepository {

    @Select("""
        SELECT bar_start, close_price, TRUE AS complete
        FROM instrument_price_history
        WHERE instrument_id = #{instrumentId}
          AND timeframe = #{timeframe.databaseValue}
          AND bar_start >= #{startInclusive}
          AND bar_start < #{endExclusive}
        ORDER BY bar_start
        """)
    List<HistoricalBar> findBars(
            @Param("instrumentId") Long instrumentId,
            @Param("timeframe") HistoricalTimeframe timeframe,
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive
    );

    @Transactional
    default int upsertBars(
            Long instrumentId,
            HistoricalTimeframe timeframe,
            List<HistoricalBar> bars
    ) {
        Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        Objects.requireNonNull(timeframe, "timeframe must not be null");
        Objects.requireNonNull(bars, "bars must not be null");
        if (bars.isEmpty()) {
            return 0;
        }
        if (bars.stream().anyMatch(bar -> bar == null || !bar.complete())) {
            throw new IllegalArgumentException("Only completed historical bars can be persisted");
        }
        return upsertCompletedBars(instrumentId, timeframe, bars);
    }

    @Insert("""
        <script>
        INSERT INTO instrument_price_history (
            instrument_id, timeframe, bar_start, close_price
        )
        VALUES
        <foreach collection="bars" item="bar" separator=",">
            (#{instrumentId}, #{timeframe.databaseValue},
             #{bar.barStart}, #{bar.closePrice})
        </foreach>
        ON CONFLICT (instrument_id, timeframe, bar_start)
        DO UPDATE SET close_price = EXCLUDED.close_price
        WHERE instrument_price_history.close_price IS DISTINCT FROM EXCLUDED.close_price
        </script>
        """)
    int upsertCompletedBars(
            @Param("instrumentId") Long instrumentId,
            @Param("timeframe") HistoricalTimeframe timeframe,
            @Param("bars") List<HistoricalBar> bars
    );
}
