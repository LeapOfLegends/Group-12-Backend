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
        WHERE holding_id = #{holdingId} AND client_id = #{client_id}
        """)
    HoldingEntity getHoldingByHoldingIdAndClientId(@Param("holdingId") Long holding_id, @Param("clientId") Long client_id);

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
            WHERE instrument_id = #{instrumentId} AND client_id = #{clientId}
            """)
    Optional<HoldingEntity> getHoldingByInstrumentIdAndClientId(@Param("instrumentId") Long instrument_id, @Param("clientId") Long client_id);

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
    HoldingEntity getHoldingByInstrumentIdAndClientIdForUpdate(@Param("instrumentId") Long instrument_id, @Param("clientId") Long client_id);

    @Update ("""
            UPDATE 
                holdings
            SET 
                quantity = #{quantity}, average_cost = #{averageCost}
            WHERE
                client_id = #{clientId} and instrument_id = #{instrumentId}
            """)
    int updateHolding(@Param("instrumentId") Long instrument_id, @Param("clientId") Long client_id, @Param("quantity") Integer quantity, @Param("averageCost") BigDecimal average_cost);

    @Select("""
        SELECT
            holding_id,
            client_id,
            instrument_id,
            quantity,
            average_cost,
            updated_at
        FROM holdings 
        WHERE holding_id = #{holdingId} AND client_id = #{client_id}
        """)
    HoldingEntity getHoldingForDeleteByIdAndClientId(@Param("holdingId") Long holding_id, @Param("clientId") Long client_id);


    @Delete (
        "DELETE FROM holdings WHERE holding_id = #{holdingId} AND client_id = #{clientId}")
    void deleteHoldingByHoldingIdAndClientId(@Param("holdingId") Long holdingId, @Param("clientId") Long clientId);


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
    HoldingEntity insert(HoldingEntity holding);
}
