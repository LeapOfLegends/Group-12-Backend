"""
Main ETL Pipeline Orchestrator
Coordinates the extract, transform, and load processes
"""

import logging
import sys
from datetime import datetime
from pathlib import Path

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('etl_pipeline.log'),
        logging.StreamHandler()
    ]
)

logger = logging.getLogger(__name__)

from config import ETLConfig
from extract import DataExtractor
from transform import DataTransformer
from load import DataLoader


class ETLPipeline:
    """Main ETL Pipeline Orchestrator"""
    
    def __init__(self):
        self.config = ETLConfig
        self.extractor = DataExtractor()
        self.transformer = DataTransformer()
        self.loader = None
        self.pipeline_start_time = None
        self.pipeline_end_time = None
    
    def initialize(self) -> bool:
        """
        Initialize pipeline connections and validate configuration
        
        Returns:
            True if initialization successful, False otherwise
        """
        logger.info("=" * 60)
        logger.info("ETL PIPELINE INITIALIZATION")
        logger.info("=" * 60)
        
        # Validate configuration
        if not self.config.validate():
            logger.error("Configuration validation failed")
            return False
        
        # Connect to OLTP database
        if not self.extractor.connect_oltp():
            logger.error("Failed to connect to OLTP database")
            return False
        
        # Connect to Data Warehouse
        if not self.extractor.connect_dw():
            logger.error("Failed to connect to Data Warehouse")
            self.extractor.close_connections()
            return False
        
        # Initialize loader with warehouse connection
        self.loader = DataLoader(self.extractor.dw_conn)
        
        logger.info("ETL Pipeline initialized successfully")
        return True
    
    def run(self, full_load: bool = False) -> bool:
        """
        Execute the complete ETL pipeline
        
        Args:
            full_load: If True, perform full load instead of incremental
            
        Returns:
            True if pipeline executed successfully, False otherwise
        """
        try:
            self.pipeline_start_time = datetime.now()
            logger.info(f"Starting ETL Pipeline - Full Load: {full_load}")
            
            # Step 1: Get watermark
            logger.info("Step 1: Retrieving watermark...")
            if full_load:
                last_filled_at = datetime(1970, 1, 1)
                logger.info("Full load mode: Starting from epoch")
            else:
                last_filled_at, last_fill_id = self.extractor.get_watermark()
                if last_filled_at is None:
                    logger.error("Failed to retrieve watermark")
                    return False
            
            # Step 2: Check for new records
            logger.info("Step 2: Checking for new records...")
            new_records_count = self.extractor.get_new_records_count(last_filled_at)
            
            if new_records_count == 0:
                logger.info("No new records to process")
                logger.info("ETL Pipeline completed successfully (no changes)")
                return True
            
            logger.info(f"Found {new_records_count} records to process")
            
            # Step 3: Extract data
            logger.info("Step 3: Extracting data from OLTP...")
            raw_records = self.extractor.extract_trade_data(last_filled_at, self.config.BATCH_SIZE)
            
            if not raw_records:
                logger.warning("No records extracted")
                return True
            
            logger.info(f"Extracted {len(raw_records)} records")
            
            # Step 4: Detect and remove duplicates
            logger.info("Step 4: Detecting duplicates...")
            records = self.transformer.detect_duplicates(raw_records)
            logger.info(f"After deduplication: {len(records)} records")
            
            # Step 5: Transform data
            logger.info("Step 5: Transforming data...")
            transformed_records = self.transformer.transform_records(records)
            logger.info(f"Transformed {len(transformed_records)} records")
            
            # Step 6: Validate records
            logger.info("Step 6: Validating records...")
            valid_records, invalid_records = self.transformer.validate_records(transformed_records)
            
            if invalid_records:
                logger.warning(f"Found {len(invalid_records)} invalid records")
                # Log invalid records for inspection
                for record in invalid_records:
                    logger.warning(f"Invalid record - Client: {record.get('client_id')}, "
                                 f"Instrument: {record.get('instrument_id')}, "
                                 f"Errors: {record.get('validation_errors')}")
            
            if not valid_records:
                logger.error("No valid records to load")
                return False
            
            logger.info(f"Validated {len(valid_records)} records as valid")
            
            # Step 7: Calculate derived metrics
            logger.info("Step 7: Calculating derived metrics...")
            enhanced_records = self.transformer.calculate_derived_metrics(valid_records)
            logger.info(f"Enhanced {len(enhanced_records)} records with metrics")
            
            # Step 8: Load into warehouse
            logger.info("Step 8: Loading data into warehouse...")
            if not self.loader.load_records(enhanced_records):
                logger.error("Failed to load records into warehouse")
                return False
            
            # Step 9: Update watermark
            logger.info("Step 9: Updating watermark...")
            max_filled_at = max(
                (record.get('filled_at') or record.get('submitted_at') 
                 for record in enhanced_records),
                default=last_filled_at
            )
            
            if not self.loader.update_watermark(self.config.PIPELINE_NAME, max_filled_at):
                logger.error("Failed to update watermark")
                return False
            
            # Step 10: Generate statistics
            logger.info("Step 10: Generating warehouse statistics...")
            stats = self.loader.get_warehouse_stats()
            self._log_statistics(stats)
            
            self.pipeline_end_time = datetime.now()
            duration = (self.pipeline_end_time - self.pipeline_start_time).total_seconds()
            
            logger.info("=" * 60)
            logger.info("ETL PIPELINE COMPLETED SUCCESSFULLY")
            logger.info(f"Duration: {duration:.2f} seconds")
            logger.info(f"Records Processed: {len(enhanced_records)}")
            logger.info(f"Invalid Records: {len(invalid_records)}")
            logger.info("=" * 60)
            
            return True
            
        except Exception as e:
            logger.error(f"Pipeline execution failed: {e}", exc_info=True)
            return False
        finally:
            self.cleanup()
    
    def _log_statistics(self, stats: dict):
        """Log warehouse statistics"""
        logger.info("-" * 60)
        logger.info("WAREHOUSE STATISTICS")
        logger.info(f"Total Records: {stats.get('total_records', 0):,}")
        
        if stats.get('records_by_status'):
            logger.info("Records by Status:")
            for status, count in stats['records_by_status'].items():
                logger.info(f"  {status}: {count:,}")
        
        if stats.get('records_by_asset_class'):
            logger.info("Records by Asset Class:")
            for asset_class, count in stats['records_by_asset_class'].items():
                logger.info(f"  {asset_class}: {count:,}")
        
        logger.info(f"Last Watermark: {stats.get('last_watermark')}")
        logger.info("-" * 60)
    
    def cleanup(self):
        """Clean up resources"""
        logger.info("Cleaning up resources...")
        self.extractor.close_connections()
        logger.info("Pipeline cleanup completed")


def main():
    """Main entry point"""
    try:
        # Parse command line arguments
        full_load = '--full-load' in sys.argv or '-f' in sys.argv
        
        # Initialize and run pipeline
        pipeline = ETLPipeline()
        
        if not pipeline.initialize():
            logger.error("Pipeline initialization failed")
            return 1
        
        if not pipeline.run(full_load=full_load):
            logger.error("Pipeline execution failed")
            return 1
        
        return 0
        
    except KeyboardInterrupt:
        logger.warning("Pipeline interrupted by user")
        return 1
    except Exception as e:
        logger.error(f"Unexpected error: {e}", exc_info=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
