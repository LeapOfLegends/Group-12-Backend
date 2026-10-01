"""
Extract module for ETL Pipeline
Extracts data from OLTP database with watermark tracking
"""

import logging
from datetime import datetime
from typing import List, Dict, Any, Optional, Tuple
import psycopg2
from psycopg2.extras import RealDictCursor
from config import ETLConfig

logger = logging.getLogger(__name__)


class DataExtractor:
    """Handles data extraction from OLTP database"""
    
    def __init__(self):
        self.config = ETLConfig
        self.oltp_conn = None
        self.dw_conn = None
    
    def connect_oltp(self) -> bool:
        """Establish connection to OLTP database"""
        try:
            self.oltp_conn = psycopg2.connect(
                host=self.config.OLTP_DB.host,
                port=self.config.OLTP_DB.port,
                database=self.config.OLTP_DB.database,
                user=self.config.OLTP_DB.username,
                password=self.config.OLTP_DB.password
            )
            logger.info("Connected to OLTP database successfully")
            return True
        except psycopg2.Error as e:
            logger.error(f"Failed to connect to OLTP database: {e}")
            return False
    
    def connect_dw(self) -> bool:
        """Establish connection to Data Warehouse"""
        try:
            self.dw_conn = psycopg2.connect(
                host=self.config.DW_DB.host,
                port=self.config.DW_DB.port,
                database=self.config.DW_DB.database,
                user=self.config.DW_DB.username,
                password=self.config.DW_DB.password
            )
            logger.info("Connected to Data Warehouse successfully")
            return True
        except psycopg2.Error as e:
            logger.error(f"Failed to connect to Data Warehouse: {e}")
            return False
    
    def get_watermark(self) -> Tuple[Optional[datetime], Optional[int]]:
        """
        Retrieve watermark (last processed timestamp and fill_id)
        Returns: (last_filled_at, last_fill_id)
        """
        try:
            with self.dw_conn.cursor(cursor_factory=RealDictCursor) as cur:
                cur.execute(f"""
                    SELECT last_filled_at, last_fill_id 
                    FROM {self.config.WATERMARK_SCHEMA}.{self.config.WATERMARK_TABLE}
                    WHERE pipeline_name = %s
                """, (self.config.PIPELINE_NAME,))
                
                result = cur.fetchone()
                if result:
                    logger.info(f"Watermark retrieved: {result['last_filled_at']}")
                    return result['last_filled_at'], result['last_fill_id']
                else:
                    logger.warning("No watermark found, starting from epoch")
                    return datetime(1970, 1, 1), None
        except psycopg2.Error as e:
            logger.error(f"Failed to retrieve watermark: {e}")
            return None, None
    
    def extract_trade_data(self, last_filled_at: datetime, batch_size: int = None) -> List[Dict[str, Any]]:
        """
        Extract trade data from OLTP database
        Joins instruments, orders, holdings, and client data
        
        Args:
            last_filled_at: Watermark timestamp to filter from
            batch_size: Number of records to fetch
            
        Returns:
            List of extracted records
        """
        if batch_size is None:
            batch_size = self.config.BATCH_SIZE
        
        try:
            with self.oltp_conn.cursor(cursor_factory=RealDictCursor) as cur:
                query = """
                    SELECT 
                        i.instrument_id,
                        c.client_id,
                        i.symbol,
                        i.instrument_name,
                        i.asset_class,
                        i.currency,
                        i.is_tradable,
                        COALESCE(i.last_price, i.ask_price, 0) as instrument_last_price,
                        c.account_balance,
                        CURRENT_TIMESTAMP as created_at,
                        o.order_type,
                        o.quantity as order_quantity,
                        o.status as order_status,
                        o.submitted_at,
                        o.accepted_at,
                        o.rejected_at,
                        o.failed_at,
                        o.filled_at,
                        o.execution_price,
                        o.trade_value,
                        COALESCE(h.quantity, 0) as holding_quantity,
                        COALESCE(h.average_cost, 0) as holdings_average_cost,
                        h.updated_at as holdings_updated
                    FROM instruments i
                    LEFT JOIN orders o ON i.instrument_id = o.instrument_id
                    LEFT JOIN clients c ON o.client_id = c.client_id
                    LEFT JOIN holdings h ON c.client_id = h.client_id 
                        AND i.instrument_id = h.instrument_id
                    WHERE o.filled_at > %s
                        OR (o.filled_at IS NULL AND o.submitted_at > %s)
                    ORDER BY o.submitted_at ASC
                    LIMIT %s
                """
                
                cur.execute(query, (last_filled_at, last_filled_at, batch_size))
                records = cur.fetchall()
                logger.info(f"Extracted {len(records)} records from OLTP database")
                return [dict(row) for row in records]
        except psycopg2.Error as e:
            logger.error(f"Failed to extract trade data: {e}")
            return []
    
    def get_new_records_count(self, last_filled_at: datetime) -> int:
        """Get count of new records to be processed"""
        try:
            with self.oltp_conn.cursor() as cur:
                cur.execute("""
                    SELECT COUNT(*) as count
                    FROM orders
                    WHERE filled_at > %s OR (filled_at IS NULL AND submitted_at > %s)
                """, (last_filled_at, last_filled_at))
                
                result = cur.fetchone()
                count = result[0] if result else 0
                logger.info(f"Found {count} new records to process")
                return count
        except psycopg2.Error as e:
            logger.error(f"Failed to get record count: {e}")
            return 0
    
    def close_connections(self):
        """Close database connections"""
        if self.oltp_conn:
            self.oltp_conn.close()
            logger.info("OLTP connection closed")
        if self.dw_conn:
            self.dw_conn.close()
            logger.info("Data Warehouse connection closed")
