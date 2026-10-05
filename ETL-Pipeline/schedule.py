"""
APScheduler-based scheduler for ETL Pipeline
Runs the ETL pipeline at 4:00 PM ET every weekday
"""

import logging
import sys
from datetime import datetime
from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.executors.pool import ThreadPoolExecutor
import pytz

logger = logging.getLogger(__name__)

# Import the main pipeline
from main import ETLPipeline


class ETLScheduler:
    """Manages scheduled execution of ETL Pipeline"""
    
    def __init__(self):
        self.scheduler = BackgroundScheduler()
        self.et_tz = pytz.timezone('America/New_York')
        
        # Configure executor
        executors = {
            'default': ThreadPoolExecutor(max_workers=1)
        }
        self.scheduler.configure(executors=executors)
    
    def start(self):
        """Start the scheduler"""
        # Schedule job: 4:00 PM ET, Monday-Friday
        # day_of_week: 0-6 (Monday-Sunday), so 0-4 is Monday-Friday
        self.scheduler.add_job(
            self.run_etl_pipeline,
            trigger=CronTrigger(
                hour=16,
                minute=00,
                day_of_week='mon-fri',
                timezone=self.et_tz
            ),
            id='etl_pipeline_job',
            name='ETL Pipeline - 4:00 PM ET Weekdays',
            replace_existing=True
        )
        
        self.scheduler.start()
        logger.info("Scheduler started. Next run:")
        job = self.scheduler.get_job('etl_pipeline_job')
        if job:
            logger.info(f"  {job.next_run_time}")
    
    def run_etl_pipeline(self):
        """Execute the ETL pipeline"""
        logger.info("=" * 60)
        logger.info("SCHEDULED ETL PIPELINE EXECUTION")
        logger.info(f"Started at: {datetime.now(self.et_tz)}")
        logger.info("=" * 60)
        
        try:
            pipeline = ETLPipeline()
            
            if not pipeline.initialize():
                logger.error("Pipeline initialization failed")
                return False
            
            success = pipeline.run(full_load=False)
            
            logger.info("=" * 60)
            if success:
                logger.info("Scheduled pipeline execution completed successfully")
            else:
                logger.error("Scheduled pipeline execution failed")
            logger.info(f"Completed at: {datetime.now(self.et_tz)}")
            logger.info("=" * 60)
            
            return success
            
        except Exception as e:
            logger.error(f"Scheduled pipeline execution failed: {e}", exc_info=True)
            return False
    
    def stop(self):
        """Stop the scheduler"""
        if self.scheduler.running:
            self.scheduler.shutdown()
            logger.info("Scheduler stopped")
    
    def get_next_run(self):
        """Get the next scheduled run time"""
        job = self.scheduler.get_job('etl_pipeline_job')
        if job:
            return job.next_run_time
        return None


def main():
    """Main entry point for scheduler"""
    
    # Setup logging
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        handlers=[
            logging.FileHandler('etl_scheduler.log'),
            logging.StreamHandler()
        ]
    )
    
    logger = logging.getLogger(__name__)
    
    try:
        logger.info("Starting ETL Scheduler...")
        scheduler = ETLScheduler()
        scheduler.start()
        
        logger.info("Scheduler is running. Press Ctrl+C to exit.")
        
        # Keep the scheduler running
        while True:
            pass
            
    except KeyboardInterrupt:
        logger.info("Scheduler interrupted by user")
        scheduler.stop()
        return 0
    except Exception as e:
        logger.error(f"Scheduler error: {e}", exc_info=True)
        scheduler.stop()
        return 1


if __name__ == "__main__":
    sys.exit(main())