"""
Load module for ETL Pipeline
Loads transformed data into the Data Warehouse and updates watermarks
"""

import logging
from typing import List, Dict, Any, Optional
from datetime import datetime
import pandas as pd
import psycopg2
from psycopg2.extras import RealDictCursor, execute_values
from config import ETLConfig

logger = logging.getLogger(__name__)


class DataLoader:
    """Handles data loading into warehouse"""
    
    def __init__(self, dw_conn):
        self.config = ETLConfig
        self.dw_conn = dw_conn
    
    @staticmethod
    def _clean_value(value: Any) -> Any:
        """
        Clean values for database insertion
        Converts pandas NaT, NaN, and other null-like values to None
        
        Args:
            value: Value to clean
            
        Returns:
            Cleaned value suitable for database insertion
        """
        # Check for pandas NaT (Not a Time)
        if pd.isna(value):
            return None
        # Check for pandas NaN
        if isinstance(value, float) and pd.isna(value):
            return None
        # Return original value if it's valid
        return value
    
    def load_records(self, records: List[Dict[str, Any]]) -> bool:
        """
        Load transformed records into warehouse table
        
        Args:
            records: List of transformed records to load
            
        Returns:
            True if successful, False otherwise
        """
        if not records:
            logger.warning("No records to load")
            return True
        
        try:
            # Prepare column names and values
            columns = [
                'instrument_id', 'client_id',
                'symbol', 'instrument_name', 'asset_class', 'currency',
                'is_tradable', 'instrument_last_price',
                'account_balance', 'created_at', 'order_type', 'order_quantity', 'order_status', 'submitted_at',
                'accepted_at', 'rejected_at', 'failed_at', 'filled_at',
                'execution_price', 'trade_value',
                'holding_quantity', 'holdings_average_cost', 'holdings_updated'
            ]
            
            # Prepare values in correct order, cleaning each value
            values = []
            for record in records:
                row = tuple(self._clean_value(record.get(col)) for col in columns)
                values.append(row)
            
            # Insert records using execute_values for efficiency
            with self.dw_conn.cursor() as cur:
                insert_query = f"""
                    INSERT INTO {self.config.TARGET_SCHEMA}.{self.config.TARGET_TABLE} 
                    ({', '.join(columns)})
                    VALUES %s
                """
                
                execute_values(cur, insert_query, values)
                self.dw_conn.commit()
                
                logger.info(f"Successfully loaded {len(records)} records into warehouse")
                return True
                
        except psycopg2.Error as e:
            logger.error(f"Failed to load records: {e}")
            self.dw_conn.rollback()
            return False
    
    def update_watermark(self, pipeline_name: str, last_filled_at: datetime, last_fill_id: Optional[int] = None) -> bool:
        """
        Update watermark after successful load
        
        Args:
            pipeline_name: Name of the pipeline
            last_filled_at: Last processed timestamp
            last_fill_id: Last processed ID (optional)
            
        Returns:
            True if successful, False otherwise
        """
        try:
            with self.dw_conn.cursor() as cur:
                update_query = f"""
                    UPDATE {self.config.WATERMARK_SCHEMA}.{self.config.WATERMARK_TABLE}
                    SET last_filled_at = %s, last_fill_id = %s, updated_at = CURRENT_TIMESTAMP
                    WHERE pipeline_name = %s
                """
                
                cur.execute(update_query, (last_filled_at, last_fill_id, pipeline_name))
                self.dw_conn.commit()
                
                logger.info(f"Watermark updated: {last_filled_at}")
                return True
                
        except psycopg2.Error as e:
            logger.error(f"Failed to update watermark: {e}")
            self.dw_conn.rollback()
            return False
    
    def get_warehouse_stats(self) -> Dict[str, Any]:
        """
        Retrieve statistics about warehouse data
        
        Returns:
            Dictionary with warehouse statistics
        """
        try:
            with self.dw_conn.cursor(cursor_factory=RealDictCursor) as cur:
                # Total records
                cur.execute(f"SELECT COUNT(*) as total_records FROM {self.config.TARGET_SCHEMA}.{self.config.TARGET_TABLE}")
                total = cur.fetchone()['total_records']
                
                # Records by status
                cur.execute(f"""
                    SELECT order_status, COUNT(*) as count 
                    FROM {self.config.TARGET_SCHEMA}.{self.config.TARGET_TABLE}
                    GROUP BY order_status
                """)
                by_status = {row['order_status']: row['count'] for row in cur.fetchall()}
                
                # Records by asset class
                cur.execute(f"""
                    SELECT asset_class, COUNT(*) as count 
                    FROM {self.config.TARGET_SCHEMA}.{self.config.TARGET_TABLE}
                    GROUP BY asset_class
                """)
                by_asset = {row['asset_class']: row['count'] for row in cur.fetchall()}
                
                # Latest watermark
                cur.execute(f"""
                    SELECT last_filled_at, updated_at 
                    FROM {self.config.WATERMARK_SCHEMA}.{self.config.WATERMARK_TABLE}
                    WHERE pipeline_name = %s
                """, (self.config.PIPELINE_NAME,))
                watermark = cur.fetchone()
                
                stats = {
                    'total_records': total,
                    'records_by_status': by_status,
                    'records_by_asset_class': by_asset,
                    'last_watermark': watermark['last_filled_at'] if watermark else None,
                    'watermark_updated': watermark['updated_at'] if watermark else None
                }
                
                logger.info(f"Warehouse stats: {total} total records")
                return stats
                
        except psycopg2.Error as e:
            logger.error(f"Failed to retrieve warehouse stats: {e}")
            return {}
    
    def cleanup_old_duplicates(self, days_retention: int = 90) -> int:
        """
        Clean up old duplicate records (keep only latest per order)
        
        Args:
            days_retention: Number of days to retain data
            
        Returns:
            Number of rows deleted
        """
        try:
            with self.dw_conn.cursor() as cur:
                # Keep only the most recent record per order_id
                cleanup_query = f"""
                    DELETE FROM {self.config.TARGET_SCHEMA}.{self.config.TARGET_TABLE}
                    WHERE ctid NOT IN (
                        SELECT max(ctid) 
                        FROM {self.config.TARGET_SCHEMA}.{self.config.TARGET_TABLE}
                        GROUP BY order_id
                    )
                    AND created_at < CURRENT_TIMESTAMP - INTERVAL '{days_retention} days'
                """
                
                cur.execute(cleanup_query)
                self.dw_conn.commit()
                deleted = cur.rowcount
                
                logger.info(f"Cleaned up {deleted} old duplicate records")
                return deleted
                
        except psycopg2.Error as e:
            logger.error(f"Failed to cleanup duplicates: {e}")
            return 0
