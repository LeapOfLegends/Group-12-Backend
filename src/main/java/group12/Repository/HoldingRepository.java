package group12.Repository;

import org.apache.ibatis.annotations.*;
import group12.Entities.HoldingEntity;
import java.util.Optional;
import java.math.BigDecimal;
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
        WHERE holding_id = #{holdingId} AND client_id = #{clientId}
        """)
    HoldingEntity getHoldingByHoldingIdAndClientId(@Param("holdingId") Long holdingId, @Param("clientId") Long clientId);

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
    List<HoldingEntity> getHoldingsByClientId(@Param("clientId") Long clientId);


    @Select ("""
            SELECT 
                holding_id,
                client_id,
                instrument_id,
                quantity,
                average_cost,
                updated_at
            FROM holdings
            WHERE instrument_id = #{instrumentId} AND client_id = #{clientId}
            """)
    Optional<HoldingEntity> getHoldingByInstrumentIdAndClientId(@Param("instrumentId") Long instrumentId, @Param("clientId") Long clientId);

    @Select ("""
            SELECT 
                holding_id,
                client_id,
                instrument_id,
                quantity,
                average_cost,
                updated_at
            FROM holdings
            WHERE instrument_id = #{instrumentId} AND client_id = #{clientId}
            FOR UPDATE
            """)
    HoldingEntity getHoldingByInstrumentIdAndClientIdForUpdate(@Param("instrumentId") Long instrumentId, @Param("clientId") Long clientId);

    @Update ("""
            UPDATE 
                holdings
            SET 
                quantity = #{quantity},
                average_cost = #{averageCost},
                updated_at = CURRENT_TIMESTAMP
            WHERE
                client_id = #{clientId} and instrument_id = #{instrumentId}
            """)
    int updateHolding(
            @Param("instrumentId") Long instrumentId,
            @Param("clientId") Long clientId,
            @Param("quantity") BigDecimal quantity,
            @Param("averageCost") BigDecimal averageCost
    );


    @Select("""
        SELECT
            holding_id,
            client_id,
            instrument_id,
            quantity,
            average_cost,
            updated_at
        FROM holdings 
        WHERE holding_id = #{holdingId} AND client_id = #{clientId}
        """)
    HoldingEntity getHoldingForDeleteByIdAndClientId(@Param("holdingId") Long holdingId, @Param("clientId") Long clientId);


    @Delete (
        "DELETE FROM holdings WHERE holding_id = #{holdingId} AND client_id = #{clientId}")
    int deleteHoldingByHoldingIdAndClientId(@Param("holdingId") Long holdingId, @Param("clientId") Long clientId);


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
    int insert(HoldingEntity holding);
}
