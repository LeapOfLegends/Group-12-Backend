package group12.Repository;

import group12.Entities.InstrumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface InstrumentRepository {

    @Select("""
        SELECT instrument_id, symbol, instrument_name, asset_class,
               currency, is_tradable, price
        FROM instruments
        ORDER BY symbol
        """)
    List<InstrumentEntity> findAll();

    @Select("""
        SELECT instrument_id, symbol, instrument_name, asset_class,
               currency, is_tradable, price
        FROM instruments
        WHERE instrument_id = #{instrumentId}
        """)
    Optional<InstrumentEntity> findById(@Param("instrumentId") Long instrumentId);

    @Select("""
        SELECT instrument_id, symbol, instrument_name, asset_class,
               currency, is_tradable, price
        FROM instruments
        WHERE symbol = #{symbol}
        """)
    Optional<InstrumentEntity> findBySymbol(@Param("symbol") String symbol);
}
