package group12.Repository;

import group12.Entities.MarketEntity;
import group12.Entities.MarketCode;
import group12.Entities.MarketStatus;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MarketRepository {

    @Select("""
        SELECT
            market_code,
            market_status
        FROM markets 
        WHERE market_code = #{marketCode} 
        """)
    MarketStatus getMarketStatusFromMarketCode(@Param("marketCode") MarketCode marketCode);
    

    @Update("""
        UPDATE markets
        SET market_status = #{marketStatus},
        WHERE market_code = #{marketCode}
        """)
    int updateMarketStatusSnapshot(
            @Param("marketStatus") MarketStatus marketStatus,
            @Param("marketCode") MarketCode marketCode
    );
    
}