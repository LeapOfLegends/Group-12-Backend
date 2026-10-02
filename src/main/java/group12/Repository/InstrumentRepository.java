package group12.Repository;

import group12.Entities.InstrumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface InstrumentRepository {

    @Select("""
        SELECT instrument_id, symbol, instrument_name, asset_class,
               currency, is_tradable AS tradable, bid_price, ask_price, last_price,
               quote_as_of, last_trade_as_of
        FROM instruments
        ORDER BY symbol
        """)
    List<InstrumentEntity> findAll();

    @Select("""
        SELECT instrument_id, symbol, instrument_name, asset_class,
               currency, is_tradable AS tradable, bid_price, ask_price, last_price,
               quote_as_of, last_trade_as_of
        FROM instruments
        WHERE is_tradable = TRUE
        ORDER BY symbol, instrument_id
        """)
    List<InstrumentEntity> findTradableInstruments();

    @Select("""
        SELECT instrument_id, symbol, instrument_name, asset_class,
               currency, is_tradable AS tradable, bid_price, ask_price, last_price,
               quote_as_of, last_trade_as_of
        FROM instruments
        WHERE instrument_id = #{instrumentId}
        """)
    Optional<InstrumentEntity> findById(@Param("instrumentId") Long instrumentId);

    @Select("""
        SELECT instrument_id, symbol, instrument_name, asset_class,
               currency, is_tradable AS tradable, bid_price, ask_price, last_price,
               quote_as_of, last_trade_as_of
        FROM instruments
        WHERE symbol = #{symbol}
        """)
    Optional<InstrumentEntity> findBySymbol(@Param("symbol") String symbol);

    @Update("""
        UPDATE instruments
        SET bid_price = #{bidPrice},
            ask_price = #{askPrice},
            quote_as_of = #{quoteAsOf}
        WHERE instrument_id = #{instrumentId}
          AND is_tradable = TRUE
          AND (quote_as_of IS NULL OR quote_as_of < #{quoteAsOf})
        """)
    int updateQuoteSnapshot(
            @Param("instrumentId") Long instrumentId,
            @Param("bidPrice") BigDecimal bidPrice,
            @Param("askPrice") BigDecimal askPrice,
            @Param("quoteAsOf") OffsetDateTime quoteAsOf
    );

    @Update("""
        UPDATE instruments
        SET last_price = #{lastPrice},
            last_trade_as_of = #{lastTradeAsOf}
        WHERE instrument_id = #{instrumentId}
          AND is_tradable = TRUE
          AND (last_trade_as_of IS NULL OR last_trade_as_of < #{lastTradeAsOf})
        """)
    int updateLastTradeSnapshot(
            @Param("instrumentId") Long instrumentId,
            @Param("lastPrice") BigDecimal lastPrice,
            @Param("lastTradeAsOf") OffsetDateTime lastTradeAsOf
    );
}
