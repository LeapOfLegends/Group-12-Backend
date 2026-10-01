"""
Transform module for ETL Pipeline
Applies business logic and data transformations
"""

import logging
from typing import List, Dict, Any
from datetime import datetime
import pandas as pd
from decimal import Decimal

logger = logging.getLogger(__name__)


class DataTransformer:
    """Handles data transformation and business logic"""
    
    @staticmethod
    def _safe_decimal_convert(value: Any, default: Any = None) -> Any:
        """
        Safely convert a value to Decimal, handling null-like values
        
        Args:
            value: Value to convert
            default: Default value if conversion fails
            
        Returns:
            Decimal or default value
        """
        # Check for pandas NaT/NaN
        if value is None or pd.isna(value):
            return default
        
        # Check for string representations of null
        if isinstance(value, str):
            if value.lower() in ('nat', 'nan', 'null', 'none', ''):
                return default
        
        try:
            return Decimal(str(value))
        except (ValueError, TypeError, Exception):
            logger.debug(f"Could not convert {value} to Decimal, using default")
            return default
    
    @staticmethod
    def transform_records(raw_records: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        Apply transformations to raw extracted data
        
        Args:
            raw_records: List of raw records from OLTP
            
        Returns:
            List of transformed records ready for warehouse
        """
        transformed_records = []
        
        for record in raw_records:
            try:
                transformed_record = DataTransformer._transform_single_record(record)
                transformed_records.append(transformed_record)
            except Exception as e:
                logger.error(f"Error transforming record (Client: {record.get('client_id')}, "
                           f"Instrument: {record.get('instrument_id')}): {e}")
                continue
        
        logger.info(f"Transformed {len(transformed_records)} records")
        return transformed_records
    
    @staticmethod
    def _transform_single_record(record: Dict[str, Any]) -> Dict[str, Any]:
        """
        Transform a single record
        
        Args:
            record: Single raw record from OLTP
            
        Returns:
            Transformed record for warehouse
        """
        # Safely convert numeric fields using the safe conversion function
        trade_value = DataTransformer._safe_decimal_convert(record.get('trade_value'))
        execution_price = DataTransformer._safe_decimal_convert(record.get('execution_price'))
        last_price = DataTransformer._safe_decimal_convert(record.get('instrument_last_price'), Decimal('0'))
        account_balance = DataTransformer._safe_decimal_convert(record.get('account_balance'), Decimal('0'))
        holdings_avg_cost = DataTransformer._safe_decimal_convert(record.get('holdings_average_cost'), Decimal('0'))
        
        transformed = {
            'instrument_id': record.get('instrument_id'),
            'client_id': record.get('client_id'),
            'symbol': record.get('symbol'),
            'instrument_name': record.get('instrument_name'),
            'asset_class': record.get('asset_class'),
            'currency': record.get('currency'),
            'is_tradable': record.get('is_tradable', False),
            'instrument_last_price': last_price,
            'account_balance': account_balance,
            'created_at': record.get('created_at'),
            'order_type': record.get('order_type'),
            'order_quantity': record.get('order_quantity'),
            'order_status': record.get('order_status'),
            'submitted_at': record.get('submitted_at'),
            'accepted_at': record.get('accepted_at'),
            'rejected_at': record.get('rejected_at'),
            'failed_at': record.get('failed_at'),
            'filled_at': record.get('filled_at'),
            'execution_price': execution_price,
            'trade_value': trade_value,
            'holding_quantity': record.get('holding_quantity', 0),
            'holdings_average_cost': holdings_avg_cost,
            'holdings_updated': record.get('holdings_updated')
        }
        
        return transformed
    
    @staticmethod
    def calculate_derived_metrics(records: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        Calculate derived metrics and aggregations
        
        Args:
            records: List of transformed records
            
        Returns:
            Records with calculated metrics
        """
        logger.info("Calculating derived metrics...")
        
        # Create DataFrame for easier aggregations
        if not records:
            return records
        
        df = pd.DataFrame(records)
        
        # Calculate metrics
        df['notional_value'] = df['execution_price'] * df['order_quantity']
        df['is_filled'] = df['filled_at'].notna()
        df['fill_rate'] = (df['is_filled'].astype(int) / len(df)) * 100 if len(df) > 0 else 0
        
        return df.to_dict('records')
    
    @staticmethod
    def validate_records(records: List[Dict[str, Any]]) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        """
        Validate records and separate valid from invalid
        
        Args:
            records: List of transformed records
            
        Returns:
            Tuple of (valid_records, invalid_records)
        """
        valid_records = []
        invalid_records = []
        
        for record in records:
            validation_errors = []
            
            # Required field validation
            if not record.get('instrument_id'):
                validation_errors.append("Missing instrument_id")
            if not record.get('client_id'):
                validation_errors.append("Missing client_id")
            
            # Data quality validation - use safe conversion
            order_qty = DataTransformer._safe_decimal_convert(record.get('order_quantity'))
            if order_qty and order_qty < 0:
                validation_errors.append("Invalid order_quantity (negative)")
            
            last_price = DataTransformer._safe_decimal_convert(record.get('instrument_last_price'))
            if last_price and last_price < 0:
                validation_errors.append("Invalid instrument_last_price (negative)")
            
            exec_price = DataTransformer._safe_decimal_convert(record.get('execution_price'))
            if exec_price and exec_price < 0:
                validation_errors.append("Invalid execution_price (negative)")
            
            # Timestamp validation
            if record.get('submitted_at') and record.get('filled_at'):
                if record['filled_at'] < record['submitted_at']:
                    validation_errors.append("filled_at before submitted_at")
            
            if validation_errors:
                record['validation_errors'] = validation_errors
                invalid_records.append(record)
                logger.warning(f"Record (Client: {record.get('client_id')}, "
                             f"Instrument: {record.get('instrument_id')}) failed validation: {validation_errors}")
            else:
                valid_records.append(record)
        
        logger.info(f"Validation complete: {len(valid_records)} valid, {len(invalid_records)} invalid")
        return valid_records, invalid_records
    
    @staticmethod
    def detect_duplicates(records: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        Detect and remove duplicate records based on client_id, instrument_id, and submitted_at
        
        Args:
            records: List of records to check
            
        Returns:
            List with duplicates removed
        """
        if not records:
            return records
        
        df = pd.DataFrame(records)
        original_count = len(df)
        
        # Drop duplicates based on client_id, instrument_id, and submitted_at
        # This combination uniquely identifies an order
        df = df.drop_duplicates(subset=['client_id', 'instrument_id', 'submitted_at'], keep='last')
        
        duplicates_removed = original_count - len(df)
        if duplicates_removed > 0:
            logger.info(f"Removed {duplicates_removed} duplicate records")
        
        return df.to_dict('records')
