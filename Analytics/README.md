# Stock Data Analyzer - histdata.py

A comprehensive Python utility for fetching, analyzing, and visualizing historical stock market data using the Alpaca Trading API.

## Overview

The `histdata.py` script provides a `StockDataAnalyzer` class that enables you to:
- Fetch end-of-day (EOD) historical stock price data from Alpaca
- Perform statistical analysis on stock prices and trading volume
- Calculate moving averages and volatility metrics
- Generate professional visualizations of stock performance
- Cache data locally to minimize API calls

## Features

### Data Fetching
- **Dual-mode data retrieval**: Tries Alpaca SDK first, automatically falls back to REST API if needed
- **Flexible date ranges**: Query any date range within API limits
- **End-of-day data**: Compatible with Alpaca's free plan (daily data only)
- **Intelligent caching**: Stores fetched data in memory to avoid redundant API calls

### Analysis Capabilities
- **Price Statistics**: Min/max opening and closing prices, average prices, price changes (absolute and percentage)
- **Volume Analysis**: Total trading volume, average volume, min/max volume
- **Volatility Metrics**: Daily returns standard deviation and mean
- **Moving Averages**: Calculate rolling averages over custom time windows (default: 20 days)
- **Latest Prices**: Retrieve the most recent closing price

### Visualization
- **Two-panel charts**: 
  - Top panel: Line graph of closing price over time
  - Bottom panel: Bar chart of trading volume over time
- **Flexible output**: Save as PNG or display interactively
- **Robust data handling**: Automatically handles different data formats and column naming conventions

## Requirements

### Dependencies
```
pandas
matplotlib
seaborn
requests
alpaca-trade-api
python-dotenv
sqlalchemy
```

### API Access
- Alpaca API account with valid API credentials
- Free or paid Alpaca tier (free tier supports EOD data only)

## Installation

1. **Install required packages**:
   ```bash
   pip install pandas matplotlib seaborn requests alpaca-trade-api python-dotenv sqlalchemy
   ```

2. **Set up environment variables**:
   Create a `.env` file in your project root with:
   ```
   ALPACA_API_KEY=your_alpaca_api_key_here
   ALPACA_SECRET_KEY=your_alpaca_secret_key_here
   ```

3. **Verify API connectivity**:
   The script will automatically attempt to initialize the Alpaca client. If credentials are invalid, it will fall back to REST API mode.

## Usage

### Basic Example

```python
from histdata import StockDataAnalyzer

# Initialize the analyzer
analyzer = StockDataAnalyzer()

# Fetch data
symbol = "AAPL"
start_date = "2024-01-01"
end_date = "2024-12-31"

data = analyzer.fetch_historical_data(symbol, start_date, end_date)

# Perform analysis
analysis = analyzer.analyze_data(symbol, data)
print(analysis)

# Get latest price
latest_price = analyzer.get_latest_price(symbol, data)
print(f"Latest closing price: ${latest_price:.2f}")

# Calculate 20-day moving average
ma_20 = analyzer.calculate_moving_average(symbol, window=20, data=data)
print(f"20-day MA: ${ma_20.iloc[-1]:.2f}")

# Generate visualization
analyzer.visualize_data(symbol, data, save_path="stock_chart.png")
```

### Running as Standalone Script

```bash
python histdata.py
```

The script will fetch AAPL data for 2025, perform analysis, and generate a visualization in:
```
Analytics/exports/visualizations/stock_analysis.png
```

## API Reference

### `StockDataAnalyzer` Class

#### `__init__(api_key=None, secret_key=None)`
Initializes the analyzer with API credentials from environment variables or provided arguments.

#### `fetch_historical_data(symbol, start_date, end_date=None, use_sdk=True)`
Fetches historical EOD stock data.
- **Parameters**:
  - `symbol` (str): Stock ticker (e.g., 'AAPL', 'MSFT')
  - `start_date` (str or datetime): Start date in 'YYYY-MM-DD' format
  - `end_date` (str or datetime): End date (defaults to today)
  - `use_sdk` (bool): Whether to try SDK first
- **Returns**: pandas DataFrame with OHLCV data

#### `analyze_data(symbol, data=None)`
Performs comprehensive statistical analysis on stock data.
- **Returns**: Dictionary containing:
  - `price_stats`: Open/close ranges, averages, price change
  - `volume_stats`: Volume totals, averages, ranges
  - `volatility`: Daily returns standard deviation and mean

#### `get_latest_price(symbol, data=None)`
Returns the most recent closing price.
- **Returns**: Float or None

#### `calculate_moving_average(symbol, window=20, data=None)`
Calculates rolling average over specified window.
- **Parameters**:
  - `window` (int): Number of days for rolling average
- **Returns**: pandas Series with moving average values

#### `visualize_data(symbol, data=None, save_path=None)`
Creates and saves/displays visualization charts.
- **Parameters**:
  - `save_path` (str): Path to save PNG (if None, displays interactively)

#### `query_data(symbol, start_date=None, end_date=None)`
Filters cached data by date range.
- **Returns**: Filtered pandas DataFrame

#### `get_cached_data(symbol)`
Retrieves previously fetched data from cache.
- **Returns**: pandas DataFrame or None

## Data Structure

### Input Data Format
The API returns data with the following columns:
- `open`: Opening price
- `high`: Highest price of the day
- `low`: Lowest price of the day
- `close`: Closing price
- `volume`: Trading volume
- `trade_count`: Number of trades (optional)
- `vwap`: Volume-weighted average price (optional)

### Output Data Format
DataFrames are indexed by timestamp (datetime) with OHLCV columns standardized.

## Output Files

When running the script, visualizations are saved to:
```
Analytics/exports/visualizations/stock_analysis.png
```

The PNG file contains:
- **Top chart**: Closing price line graph with grid and legend
- **Bottom chart**: Trading volume bar chart with grid and legend

## Error Handling

The script includes robust error handling:
- **API Failures**: Automatically falls back from SDK to REST API
- **Missing Data**: Returns empty DataFrames with informative messages
- **Invalid Dates**: Converts string dates to datetime objects automatically
- **Data Format Issues**: Handles both full and abbreviated column names
- **MultiIndex Data**: Properly extracts symbol-specific data

## Limitations

- **Free Plan**: End-of-day data only (no intraday data)
- **Rate Limiting**: Alpaca API has rate limits (check official documentation)
- **Historical Data**: Limited by Alpaca's historical data retention
- **Single Symbol**: Each call fetches one symbol at a time

## Troubleshooting

### "No data returned for [SYMBOL]"
- Verify the ticker symbol is valid
- Check date range is valid
- Ensure API credentials are correct

### Visualization shows empty plots
- Verify data was successfully fetched (check console output)
- Ensure the symbol exists in the fetched data
- Check that `save_path` directory exists and is writable

### API authentication errors
- Verify `.env` file contains correct API keys
- Check keys are not expired or revoked in Alpaca dashboard
- Ensure no extra whitespace in environment variables

### "Using fallback method" message
- This is normal if SDK initialization fails
- Script will continue using REST API
- Verify internet connection is available

## Notes

- Data is cached in memory during script execution to minimize API calls
- Timestamps are in UTC timezone (may show with +00:00 offset)
- Moving averages require minimum data points (20+ days for 20-day MA)
- All financial calculations are performed using pandas native functions



