package group12.Repository;

import org.apache.ibatis.annotations.*;
import group12.Entities.HoldingEntity;
import java.util.Optional;
import java.util.List;


@Mapper
public interface HoldingRepository {

    @Select("""
        SELECT
            holding_id,
            client_id,
            instrument_id,
            quantity,
            average_cost,
            updated_at
        FROM holdings 
        WHERE holding_id = #{holdingId}
        """)
    Optional<HoldingEntity> getHoldingByHoldingId(@Param("holdingId") Long holding_id);

    @Select ("""
            SELECT 
                holding_id,
                client_id,
                instrument_id,
                quantity,
                average_cost,
                updated_at
            FROM holdings
            WHERE client_id = #{clientId}
            """)
    List<HoldingEntity> getHoldingsByClientId(@Param("clientId") Long client_id);


    @Select ("""
            SELECT 
                holding_id,
                client_id,
                instrument_id,
                quantity,
                average_cost,
                updated_at
            FROM holdings
            WHERE instrument_id = #{instrumentId}
            """)
    List<HoldingEntity> getHoldingsByInstrumentId(@Param("instrumentId") Long instrument_id);


    @Insert("""
            INSERT INTO holdings (
                client_id,
                instrument_id,
                quantity,
                average_cost
            )
            VALUES (
                #{clientId},
                #{instrumentId},
                #{quantity},
                #{averageCost}
            )
            """)
    @Options(
        useGeneratedKeys =true,
        keyProperty = "holdingId",
        keyColumn = "holding_id"
    )

    void insert(HoldingEntity holding);

 
}
