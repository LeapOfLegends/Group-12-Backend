"""
Configuration module for ETL Pipeline
Manages database connections and environment variables
"""

import os
from dataclasses import dataclass
from typing import Optional
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()


@dataclass
class DatabaseConfig:
    """Database configuration class"""
    host: str
    port: int
    database: str
    username: str
    password: str
    
    def get_connection_string(self) -> str:
        """Generate PostgreSQL connection string"""
        return f"postgresql://{self.username}:{self.password}@{self.host}:{self.port}/{self.database}"


class ETLConfig:
    """ETL Pipeline Configuration"""
    
    # OLTP Database Configuration (Source)
    OLTP_DB = DatabaseConfig(
        host=os.getenv("DB_HOST", "localhost"),
        port=int(os.getenv("DB_PORT", "15432")),
        database=os.getenv("DB_NAME", "capstone2026"),
        username=os.getenv("DB_USER", "postgres"),
        password=os.getenv("DB_PASSWORD", "")
    )
    
    # Data Warehouse Configuration (Target)
    DW_DB = DatabaseConfig(
        host=os.getenv("WH_DB_HOST", "localhost"),
        port=int(os.getenv("WH_DB_PORT", "15432")),
        database=os.getenv("WH_DB_NAME", "data_warehouse"),
        username=os.getenv("WH_DB_USER", "postgres"),
        password=os.getenv("WH_DB_PASSWORD", "")
    )
    
    # ETL Configuration
    BATCH_SIZE = int(os.getenv("ETL_BATCH_SIZE", "1000"))
    LOG_LEVEL = os.getenv("ETL_LOG_LEVEL", "INFO")
    
    # Watermark Configuration
    WATERMARK_SCHEMA = "dw"
    WATERMARK_TABLE = "etl_watermark"
    PIPELINE_NAME = "trade_activity"
    
    # Target Table Configuration
    TARGET_SCHEMA = "dw"
    TARGET_TABLE = "table_records"
    
    @classmethod
    def validate(cls) -> bool:
        """Validate configuration"""
        try:
            if not cls.OLTP_DB.password:
                print("WARNING: OLTP_DB_PASSWORD not set, using empty password")
            if not cls.DW_DB.password:
                print("WARNING: DW_DB_PASSWORD not set, using empty password")
            return True
        except Exception as e:
            print(f"Configuration validation failed: {e}")
            return False
