# ETL Pipeline for Data Warehouse

A comprehensive Extract-Transform-Load (ETL) pipeline that moves trade activity data from the OLTP database (`capstone2026`) to the Data Warehouse (`data_warehouse`) with validation, deduplication, and metrics calculation.

## Architecture Overview

```
OLTP Database (capstone2026)
    ↓
[EXTRACT] - Extract raw data from source tables
    ↓
[TRANSFORM] - Validate, deduplicate, enhance with derived metrics
    ↓
[LOAD] - Insert into warehouse, update watermark
    ↓
Data Warehouse (data_warehouse)
```

## Components

### 1. **config.py**
Central configuration management for:
- OLTP database connection settings
- Data Warehouse connection settings
- ETL parameters (batch size, logging level)
- Watermark and target table configuration

**Key Features:**
- Environment variable support via `.env` file
- Database configuration validation
- Secure credential handling

### 2. **extract.py**
Handles data extraction from the OLTP database:
- Establishes connections to both OLTP and DW databases
- Retrieves watermarks for incremental processing
- Extracts trade data with related entities (instruments, orders, holdings, clients)
- Counts new records available for processing

**Key Methods:**
- `connect_oltp()` - Connect to source database
- `connect_dw()` - Connect to warehouse database
- `get_watermark()` - Retrieve last processed timestamp
- `extract_trade_data()` - Extract new/modified records
- `get_new_records_count()` - Check for pending data

### 3. **transform.py**
Applies business logic and data transformations:
- Converts raw data to warehouse schema format
- Validates data quality and completeness
- Detects and removes duplicates
- Calculates derived metrics (notional value, fill rates, etc.)

**Key Methods:**
- `transform_records()` - Apply transformations to batch
- `validate_records()` - Data quality validation with error reporting
- `detect_duplicates()` - Remove duplicate records based on order_id
- `calculate_derived_metrics()` - Compute derived fields

### 4. **load.py**
Manages data loading into the Data Warehouse:
- Bulk inserts transformed records
- Handles conflict resolution (updates on duplicate key)
- Updates ETL watermarks
- Provides warehouse statistics
- Cleans up old duplicate records

**Key Methods:**
- `load_records()` - Insert/update records in warehouse
- `update_watermark()` - Track last processed timestamp
- `get_warehouse_stats()` - Report warehouse metrics
- `cleanup_old_duplicates()` - Archive old data

### 5. **main.py**
ETL Pipeline Orchestrator:
- Coordinates all pipeline stages
- Handles logging and error management
- Supports both incremental and full load modes
- Generates execution statistics and reports

**Execution Modes:**
- `python main.py` - Incremental load (default)
- `python main.py --full-load` - Full load from epoch
- `python main.py -f` - Full load (short form)

## Prerequisites

### System Requirements
- Python 3.8+
- PostgreSQL 12+
- Network access to both OLTP and DW databases

### Python Dependencies
```bash
pip install psycopg2-binary pandas python-dotenv
```

### Database Requirements
- OLTP Database: `capstone2026`
- Warehouse Database: `data_warehouse` (must be created)
- Warehouse schema: `dw` with tables:
  - `dw.table_records` (fact table)
  - `dw.etl_watermark` (watermark tracking)

## Setup Instructions

### 1. Create Virtual Environment
```bash
cd ETL-Pipeline
python -m venv .venv

# Windows
.venv\Scripts\activate

# Linux/macOS
source .venv/bin/activate
```

### 2. Install Dependencies
```bash
pip install -r requirements.txt
```

### 3. Create .env File
```bash
# .env file in ETL-Pipeline directory
OLTP_DB_HOST=localhost
OLTP_DB_PORT=15432
OLTP_DB_NAME=capstone2026
OLTP_DB_USER=postgres
OLTP_DB_PASSWORD=your_password

DW_DB_HOST=localhost
DW_DB_PORT=15432
DW_DB_NAME=data_warehouse
DW_DB_USER=postgres
DW_DB_PASSWORD=your_password

ETL_BATCH_SIZE=1000
ETL_LOG_LEVEL=INFO
```

### 4. Initialize Data Warehouse
```bash
# Run from Database-Setup directory
psql -U postgres -h localhost -p 15432 -f createWarehouse.sql
psql -U postgres -h localhost -p 15432 -d data_warehouse -f seedTables.sql
```

### 5. Create Requirements.txt
```bash
# Create requirements.txt in ETL-Pipeline directory
psycopg2-binary==2.9.9
pandas==2.1.3
python-dotenv==1.0.0
```

## Execution

### Quick Start
```bash
# Navigate to ETL-Pipeline directory
cd ETL-Pipeline

# Activate virtual environment
source .venv/bin/activate  # Linux/macOS
# or
.venv\Scripts\activate  # Windows

# Run pipeline
python main.py
```

### Incremental Load (Default)
```bash
python main.py
```
- Processes only new/modified records since last run
- Uses watermark to track progress
- Recommended for production

### Full Load
```bash
python main.py --full-load
# or
python main.py -f
```
- Reprocesses all records from epoch
- Useful for initial load or data recovery
- Use with caution in production

### Scheduled Execution (Linux/macOS)
Add to crontab for hourly execution:
```bash
0 * * * * cd /path/to/ETL-Pipeline && .venv/bin/python main.py >> etl_pipeline.log 2>&1
```

### Scheduled Execution (Windows Task Scheduler)
Create scheduled task:
```powershell
$Action = New-ScheduledTaskAction -Execute "C:\path\to\.venv\Scripts\python.exe" -Argument "C:\path\to\main.py"
$Trigger = New-ScheduledTaskTrigger -Daily -At 00:00
Register-ScheduledTask -TaskName "ETL-Pipeline" -Action $Action -Trigger $Trigger
```

## Monitoring and Logging

### Log Files
- **etl_pipeline.log** - Main execution log
- **stdout** - Real-time execution status

### Log Levels
Set in `.env` file via `ETL_LOG_LEVEL`:
- `DEBUG` - Detailed diagnostic information
- `INFO` - General execution flow (default)
- `WARNING` - Issues that don't stop execution
- `ERROR` - Critical failures

### Example Log Output
```
2024-01-15 10:30:00,123 - __main__ - INFO - ============================================================
2024-01-15 10:30:00,124 - __main__ - INFO - ETL PIPELINE INITIALIZATION
2024-01-15 10:30:00,125 - __main__ - INFO - Connected to OLTP database successfully
2024-01-15 10:30:00,200 - __main__ - INFO - Connected to Data Warehouse successfully
2024-01-15 10:30:00,201 - __main__ - INFO - Step 1: Retrieving watermark...
2024-01-15 10:30:00,220 - __main__ - INFO - Step 2: Checking for new records...
2024-01-15 10:30:00,250 - __main__ - INFO - Found 150 records to process
...
2024-01-15 10:30:15,500 - __main__ - INFO - ETL PIPELINE COMPLETED SUCCESSFULLY
2024-01-15 10:30:15,501 - __main__ - INFO - Duration: 15.38 seconds
2024-01-15 10:30:15,502 - __main__ - INFO - Records Processed: 150
```

## Data Flow Details

### Extraction
1. Connects to OLTP database
2. Retrieves current watermark (last processed timestamp)
3. Queries for new/modified orders since watermark
4. Joins with instruments, clients, and holdings data
5. Returns raw records for transformation

### Transformation
1. **Validation**: Checks required fields and data quality
2. **Deduplication**: Removes duplicates based on order_id and submitted_at
3. **Type Conversion**: Converts fields to appropriate types (Decimal for prices)
4. **Derived Metrics**: Calculates notional value, fill rates, etc.
5. **Error Tracking**: Collects validation errors for failed records

### Loading
1. Bulk inserts valid records into warehouse fact table
2. Handles conflicts with ON CONFLICT clause (updates existing records)
3. Updates ETL watermark with latest processed timestamp
4. Generates and logs warehouse statistics

## Extracted Data Schema

The pipeline extracts and transforms the following fields:

| Column | Source | Type | Description |
|--------|--------|------|-------------|
| instrument_id | instrument_entity.id | BIGINT | Unique instrument identifier |
| client_id | client_entity.id | BIGINT | Client making the trade |
| order_id | order_entity.id | BIGINT | Order identifier |
| holding_id | holding_entity.id | BIGINT | Client holding identifier |
| symbol | instrument_entity.symbol | VARCHAR | Trading symbol |
| instrument_name | instrument_entity.name | VARCHAR | Full instrument name |
| asset_class | instrument_entity.asset_class | VARCHAR | Asset class category |
| order_status | order_entity.status | VARCHAR | Current order status |
| filled_at | order_entity.filled_at | TIMESTAMPTZ | When order was filled |
| execution_price | order_entity.execution_price | NUMERIC | Price at execution |
| trade_value | Calculated | NUMERIC | execution_price × quantity |

## Error Handling

### Invalid Records
Records failing validation are logged but don't stop the pipeline:
```
WARNING: Record 12345 failed validation: Invalid order_quantity (negative)
```

### Database Errors
Connection failures trigger immediate pipeline termination with detailed error logs.

### Retry Strategy
- No automatic retries (external orchestration recommended)
- Check logs and resolve issues manually
- Re-run pipeline after fixing issues

## Performance Considerations

### Batch Processing
- Default batch size: 1,000 records
- Adjust `BATCH_SIZE` in config for your hardware
- Larger batches = faster but more memory usage

### Incremental vs Full Load
- **Incremental Load**: ~1-2 seconds for no changes, seconds to minutes for new data
- **Full Load**: Minutes to hours depending on database size

### Optimization Tips
1. Create indexes on source tables (submitted_at, filled_at)
2. Schedule during off-peak hours
3. Adjust batch size based on server resources
4. Run full load periodically (weekly/monthly)

## Troubleshooting

### Connection Errors
```
Failed to connect to OLTP database: connection refused
```
**Solution**: Verify database is running and credentials in `.env` are correct

### Missing Watermark
```
No watermark found, starting from epoch
```
**Expected**: First run will process all historical data

### Validation Failures
```
Found 5 invalid records
```
**Solution**: Check logs for specific validation errors and fix source data

### Memory Issues
```
MemoryError during transformation
```
**Solution**: Reduce `BATCH_SIZE` in config

## Advanced Configuration

### Custom Validation Rules
Edit `transform.py` `validate_records()` method to add custom rules

### Custom Transformations
Edit `transform.py` `_transform_single_record()` for field mappings

### Custom Metrics
Edit `transform.py` `calculate_derived_metrics()` for new calculations

## Maintenance

### Regular Tasks
- Monitor `etl_pipeline.log` for errors
- Check warehouse statistics (logged at end of each run)
- Verify watermark is advancing
- Run cleanup occasionally: Call `loader.cleanup_old_duplicates()` in main.py

### Periodic Checks
- **Weekly**: Verify record counts are within expected ranges
- **Monthly**: Run full load to validate data integrity
- **Quarterly**: Review and optimize batch sizes and performance

## Support

For issues or questions:
1. Check `etl_pipeline.log` for detailed error messages
2. Verify database connectivity and credentials
3. Ensure schema exists in data warehouse
4. Review data quality in source system
5. Check Python dependencies are installed

## Version History

- **v1.0.0** - Initial release
  - Incremental and full load support
  - Data validation and deduplication
  - Watermark tracking
  - Comprehensive logging

---

**Created**: 2024-01-15
**Last Updated**: 2024-01-15
**Status**: Production Ready
