package group12.Repository;

import group12.Entities.MarketCode;
import group12.Entities.MarketEntity;
import group12.Entities.MarketStatus;
import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;

@Mapper
public interface MarketRepository {

    @Select("""
        SELECT
            market_status
        FROM markets 
        WHERE market_code = #{marketCode} 
        """)
    MarketStatus getMarketStatusByMarketCode(@Param("marketCode") MarketCode marketCode);

    @Select("""
        SELECT
            market_id,
            market_code,
            market_status,
            status_as_of
        FROM markets 
        WHERE market_code = #{marketCode} 
        """)
    MarketEntity getMarketEntityForUpdate(@Param("marketCode") MarketCode marketCode);
    

    @Update("""
        UPDATE markets
        SET market_status = #{marketStatus},
            status_as_of = #{statusAsOf}
        WHERE market_code = #{marketCode}
        """)
    int updateMarketStatusSnapshot(
            @Param("marketStatus") MarketStatus marketStatus,
            @Param("marketCode") MarketCode marketCode,
            @Param("statusAsOf") OffsetDateTime statusAsOf
    );
    
}