from sqlalchemy import create_engine
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt
from alpaca.data.historical import StockHistoricalDataClient
from alpaca.data.requests import StockBarsRequest
from alpaca.data.timeframe import TimeFrame
import requests
from dotenv import load_dotenv
from datetime import datetime


load_dotenv()

import os

ALPACA_API_KEY = os.getenv("ALPACA_API_KEY")
ALPACA_SECRET_KEY = os.getenv("ALPACA_SECRET_KEY")

# Initialize Alpaca Historical Data Client (works with free plan)
# Note: Free plan supports end-of-day data only
try:
    data_client = StockHistoricalDataClient(ALPACA_API_KEY, ALPACA_SECRET_KEY)
except Exception as e:
    print(f"Note: Using fallback method for data fetching. Error: {e}")
    data_client = None


class StockDataAnalyzer:
    """
    Class to fetch, query, and analyze historical stock data from Alpaca API.
    
    Compatible with Alpaca's free plan. Features:
    - End-of-day (EOD) historical data only
    - Daily timeframe
    - Automatic fallback from SDK to REST API if needed
    - Supports past data queries up to the API limits
    
    Free Plan Limitations:
    - Only EOD (daily) data available
    - No real-time or intraday data
    - Rate limiting applies (check Alpaca docs)
    """
    
    def __init__(self, api_key=None, secret_key=None):
        """
        Initialize the StockDataAnalyzer.
        
        Args:
            api_key (str): Alpaca API key. Defaults to ALPACA_API_KEY env variable.
            secret_key (str): Alpaca Secret key. Defaults to ALPACA_SECRET_KEY env variable.
        """
        self.api_key = api_key or ALPACA_API_KEY
        self.secret_key = secret_key or ALPACA_SECRET_KEY
        self.base_url = "https://data.alpaca.markets"
        self.data_client = data_client
        self.data_cache = {}
    
    def fetch_historical_data(self, symbol, start_date, end_date=None, use_sdk=True):
        """
        Fetch historical EOD stock data for a given symbol.
        
        Args:
            symbol (str): Stock ticker symbol (e.g., 'AAPL')
            start_date (str or datetime): Start date in format 'YYYY-MM-DD'
            end_date (str or datetime): End date in format 'YYYY-MM-DD'. Defaults to today.
            use_sdk (bool): Try SDK first, then fallback to REST API if needed.
        
        Returns:
            pd.DataFrame: DataFrame with columns [timestamp, open, high, low, close, volume]
        """
        if end_date is None:
            end_date = datetime.now().strftime('%Y-%m-%d')
        
        # Convert dates to datetime if string
        if isinstance(start_date, str):
            start_date = datetime.strptime(start_date, '%Y-%m-%d')
        if isinstance(end_date, str):
            end_date = datetime.strptime(end_date, '%Y-%m-%d')
        
        # Try SDK first if available and requested
        if use_sdk and self.data_client:
            try:
                bars_request = StockBarsRequest(
                    symbol_or_symbols=symbol,
                    timeframe=TimeFrame.Day,
                    start=start_date,
                    end=end_date
                )
                bars = self.data_client.get_stock_bars(bars_request)
                df = bars.df
                
                # Cache the data
                self._cache_data(symbol, df)
                return df
            except Exception as e:
                print(f"SDK fetch failed for {symbol}: {e}. Attempting REST API fallback...")
        
        # Fallback to REST API
        return self._fetch_via_rest_api(symbol, start_date, end_date)
    
    def _fetch_via_rest_api(self, symbol, start_date, end_date):
        """
        Fallback method to fetch data via Alpaca REST API.
        
        Args:
            symbol (str): Stock ticker symbol
            start_date (datetime): Start date
            end_date (datetime): End date
        
        Returns:
            pd.DataFrame: DataFrame with OHLCV data
        """
        try:
            url = f"{self.base_url}/v1/bars"
            
            params = {
                'symbols': symbol,
                'timeframe': 'day',
                'start': start_date.strftime('%Y-%m-%dT00:00:00Z'),
                'end': end_date.strftime('%Y-%m-%dT00:00:00Z'),
                'limit': 10000,
                'adjustment': 'all',
                'feed': 'sip'
            }
            
            headers = {
                'APCA-API-KEY-ID': self.api_key
            }
            
            response = requests.get(url, params=params, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            bars = data.get('bars', {}).get(symbol, [])
            
            if not bars:
                print(f"No data returned for {symbol}")
                return pd.DataFrame()
            
            df = pd.DataFrame(bars)
            
            # Debug: Print actual columns received
            print(f"Columns in API response: {df.columns.tolist()}")
            
            # Handle various column naming conventions
            # Map full names to short names for consistency
            column_mapping = {
                'open': 'o',
                'high': 'h',
                'low': 'l',
                'close': 'c',
                'volume': 'v',
                'trade_count': 'n',
                'vwap': 'vw',
                # Also handle short names if they come through
                'o': 'o', 'h': 'h', 'l': 'l', 'c': 'c', 'v': 'v', 'n': 'n', 'vw': 'vw'
            }
            
            # Create a mapping of actual columns to standard names
            new_columns = {}
            for col in df.columns:
                if col in column_mapping:
                    new_columns[col] = column_mapping[col]
                else:
                    new_columns[col] = col
            
            df = df.rename(columns=new_columns)
            
            # Convert timestamp to datetime and set as index
            # Handle both 't' and 'timestamp' column names
            # For REST API, we need to add a timestamp column from the data structure
            # The timestamp might be in the URL structure, so we'll create it from the bar data if needed
            if 't' not in df.columns and 'timestamp' not in df.columns:
                # If no timestamp column exists, we might need to infer it
                # For now, just ensure we have proper index
                pass
            else:
                timestamp_col = 't' if 't' in df.columns else 'timestamp'
                df[timestamp_col] = pd.to_datetime(df[timestamp_col])
                df.set_index(timestamp_col, inplace=True)
                df.index.name = 't'
            
            # Ensure index is a regular DatetimeIndex (not MultiIndex)
            if isinstance(df.index, pd.MultiIndex):
                df.reset_index(level=0, drop=True, inplace=True)
            
            # Cache the data
            self._cache_data(symbol, df)
            return df
            
        except requests.exceptions.RequestException as e:
            print(f"REST API request failed: {e}")
            return pd.DataFrame()
        except Exception as e:
            print(f"Error processing REST API data: {e}")
            import traceback
            traceback.print_exc()
            return pd.DataFrame()
    
    def _cache_data(self, symbol, df):
        """Store fetched data in cache."""
        self.data_cache[symbol] = df
    
    def get_cached_data(self, symbol):
        """
        Retrieve cached data for a symbol.
        
        Args:
            symbol (str): Stock ticker symbol
        
        Returns:
            pd.DataFrame: Cached data or None if not cached
        """
        return self.data_cache.get(symbol)
    
    def query_data(self, symbol, start_date=None, end_date=None):
        """
        Query data for a symbol within a date range.
        
        Args:
            symbol (str): Stock ticker symbol
            start_date (str): Start date filter in 'YYYY-MM-DD' format
            end_date (str): End date filter in 'YYYY-MM-DD' format
        
        Returns:
            pd.DataFrame: Filtered data
        """
        df = self.get_cached_data(symbol)
        
        if df is None or df.empty:
            print(f"No cached data for {symbol}. Fetch data first.")
            return pd.DataFrame()
        
        if start_date:
            df = df[df.index >= start_date]
        if end_date:
            df = df[df.index <= end_date]
        
        return df
    
    def analyze_data(self, symbol, data=None):
        """
        Analyze historical data for a stock symbol.
        
        Args:
            symbol (str): Stock ticker symbol
            data (pd.DataFrame): Optional data to analyze. Defaults to cached data.
        
        Returns:
            dict: Analysis results including statistics, trends, etc.
        """
        if data is None:
            data = self.get_cached_data(symbol)
        
        if data is None or data.empty:
            print(f"No data available for {symbol}")
            return {}
        
        # Handle MultiIndex - extract just the data for the symbol
        if isinstance(data.index, pd.MultiIndex):
            # Reset the index to make symbol a column, then filter
            data = data.reset_index()
            if 'symbol' in data.columns:
                data = data[data['symbol'] == symbol]
            data = data.set_index('timestamp')
        
        # Ensure data is sorted by date
        data = data.sort_index()
        
        # Get the first and last index values safely
        first_idx = data.index[0]
        last_idx = data.index[-1]
        
        # Convert to date if it's a datetime-like object
        if hasattr(first_idx, 'date'):
            first_date = first_idx.date()
        elif isinstance(first_idx, (list, tuple)):
            first_date = first_idx[-1] if isinstance(first_idx[-1], str) else str(first_idx[-1])
        else:
            first_date = str(first_idx)
        
        if hasattr(last_idx, 'date'):
            last_date = last_idx.date()
        elif isinstance(last_idx, (list, tuple)):
            last_date = last_idx[-1] if isinstance(last_idx[-1], str) else str(last_idx[-1])
        else:
            last_date = str(last_idx)
        
        analysis = {
            'symbol': symbol,
            'period': f"{first_date} to {last_date}",
            'total_days': len(data),
        }
        
        # Try both short and long column names
        open_col = 'o' if 'o' in data.columns else ('open' if 'open' in data.columns else None)
        close_col = 'c' if 'c' in data.columns else ('close' if 'close' in data.columns else None)
        high_col = 'h' if 'h' in data.columns else ('high' if 'high' in data.columns else None)
        low_col = 'l' if 'l' in data.columns else ('low' if 'low' in data.columns else None)
        volume_col = 'v' if 'v' in data.columns else ('volume' if 'volume' in data.columns else None)
        
        # Only include price statistics if we have the required columns
        if all([open_col, close_col, high_col, low_col]):
            analysis['price_stats'] = {
                'open_min': data[open_col].min(),
                'open_max': data[open_col].max(),
                'close_min': data[close_col].min(),
                'close_max': data[close_col].max(),
                'high_max': data[high_col].max(),
                'low_min': data[low_col].min(),
                'close_avg': data[close_col].mean(),
                'price_change': data[close_col].iloc[-1] - data[close_col].iloc[0],
                'price_change_pct': ((data[close_col].iloc[-1] - data[close_col].iloc[0]) / data[close_col].iloc[0] * 100)
            }
        
        # Only include volume statistics if we have the volume column
        if volume_col:
            analysis['volume_stats'] = {
                'volume_total': data[volume_col].sum(),
                'volume_avg': data[volume_col].mean(),
                'volume_min': data[volume_col].min(),
                'volume_max': data[volume_col].max()
            }
        
        # Only include volatility if we have closing prices
        if close_col:
            analysis['volatility'] = {
                'daily_returns_std': data[close_col].pct_change().std() * 100,
                'daily_returns_mean': data[close_col].pct_change().mean() * 100
            }
        
        return analysis
    
    def get_latest_price(self, symbol, data=None):
        """
        Get the latest closing price for a symbol.
        
        Args:
            symbol (str): Stock ticker symbol
            data (pd.DataFrame): Optional data. Defaults to cached data.
        
        Returns:
            float: Latest closing price or None
        """
        if data is None:
            data = self.get_cached_data(symbol)
        
        if data is None or data.empty:
            return None
        
        # Handle MultiIndex
        if isinstance(data.index, pd.MultiIndex):
            data = data.reset_index()
            if 'symbol' in data.columns:
                data = data[data['symbol'] == symbol]
        
        # Try both short and long column names
        close_col = 'c' if 'c' in data.columns else ('close' if 'close' in data.columns else None)
        
        if close_col is None or close_col not in data.columns:
            return None
        
        return data[close_col].iloc[-1]
    
    def calculate_moving_average(self, symbol, window=20, data=None):
        """
        Calculate moving average for a symbol.
        
        Args:
            symbol (str): Stock ticker symbol
            window (int): Window size for moving average calculation
            data (pd.DataFrame): Optional data. Defaults to cached data.
        
        Returns:
            pd.Series: Moving average series
        """
        if data is None:
            data = self.get_cached_data(symbol)
        
        if data is None or data.empty:
            return pd.Series()
        
        # Handle MultiIndex
        if isinstance(data.index, pd.MultiIndex):
            data = data.reset_index()
            if 'symbol' in data.columns:
                data = data[data['symbol'] == symbol]
            if 'timestamp' in data.columns:
                data = data.set_index('timestamp')
        
        # Try both short and long column names
        close_col = 'c' if 'c' in data.columns else ('close' if 'close' in data.columns else None)
        
        if close_col is None or close_col not in data.columns:
            return pd.Series()
        
        return data[close_col].rolling(window=window).mean()
    
    def visualize_data(self, symbol, data=None, save_path=None):
        """
        Create visualizations for stock data.
        
        Args:
            symbol (str): Stock ticker symbol
            data (pd.DataFrame): Optional data. Defaults to cached data.
            save_path (str): Optional path to save the plot
        """
        if data is None:
            data = self.get_cached_data(symbol)
        
        if data is None or data.empty:
            print(f"No data available for {symbol}")
            return
        
        # Handle MultiIndex - extract data for the specific symbol
        if isinstance(data.index, pd.MultiIndex):
            data = data.reset_index()
            if 'symbol' in data.columns:
                data = data[data['symbol'] == symbol]
            if 'timestamp' in data.columns:
                data = data.set_index('timestamp')
            elif 't' in data.columns:
                data = data.set_index('t')
        
        # Determine which column names are available
        close_col = 'close' if 'close' in data.columns else ('c' if 'c' in data.columns else None)
        volume_col = 'volume' if 'volume' in data.columns else ('v' if 'v' in data.columns else None)
        
        fig, axes = plt.subplots(2, 1, figsize=(12, 8))
        
        # Plot closing price if available
        if close_col and close_col in data.columns and not data[close_col].empty:
            axes[0].plot(data.index, data[close_col], label='Close Price', linewidth=2, color='blue')
            axes[0].set_title(f'{symbol} - Closing Price Over Time')
            axes[0].set_ylabel('Price ($)')
            axes[0].legend()
            axes[0].grid(True, alpha=0.3)
        else:
            axes[0].text(0.5, 0.5, 'No closing price data available', 
                        ha='center', va='center', transform=axes[0].transAxes)
            axes[0].set_title(f'{symbol} - Closing Price Over Time')
        
        # Plot volume if available
        if volume_col and volume_col in data.columns and not data[volume_col].empty:
            axes[1].bar(data.index, data[volume_col], label='Volume', alpha=0.7, color='green')
            axes[1].set_title(f'{symbol} - Trading Volume Over Time')
            axes[1].set_xlabel('Date')
            axes[1].set_ylabel('Volume')
            axes[1].legend()
            axes[1].grid(True, alpha=0.3)
        else:
            axes[1].text(0.5, 0.5, 'No volume data available', 
                        ha='center', va='center', transform=axes[1].transAxes)
            axes[1].set_title(f'{symbol} - Trading Volume Over Time')
        
        plt.tight_layout()
        
        if save_path:
            plt.savefig(save_path)
            print(f"Plot saved to {save_path}")
        else:
            plt.show()


# ============================================================================
# Example Usage
# ============================================================================

if __name__ == "__main__":
    """
    Example usage of the StockDataAnalyzer class.
    """
    
    # Initialize the analyzer
    analyzer = StockDataAnalyzer()
    
    # Example: Fetch historical data for AAPL
    #Note: Will have to replace symbol with the desired stock ticker when needed as well as the date range
    symbol = "AAPL"
    start_date = "2025-01-01"
    end_date = "2025-12-31"
    
    print(f"Fetching historical data for {symbol} from {start_date} to {end_date}...")
    try:
        data = analyzer.fetch_historical_data(symbol, start_date, end_date)
        
        if not data.empty:
            print(f"Successfully fetched {len(data)} days of data")
            print(f"Data shape: {data.shape}")
            print(f"Data columns: {data.columns.tolist()}")
            print(f"Data index type: {type(data.index)}")
            print(f"First few rows:\n{data.head()}\n")
            
            # Analyze the data
            print("\n" + "="*60)
            print(f"Analysis for {symbol}")
            print("="*60)
            analysis = analyzer.analyze_data(symbol, data)
            
            for category, details in analysis.items():
                if isinstance(details, dict):
                    print(f"\n{category.upper()}:")
                    for key, value in details.items():
                        if isinstance(value, float):
                            print(f"  {key}: {value:.2f}")
                        else:
                            print(f"  {key}: {value}")
                else:
                    print(f"{category}: {details}")
            
            # Get latest price
            latest_price = analyzer.get_latest_price(symbol, data)
            if latest_price is not None:
                print(f"\nLatest closing price: ${latest_price:.2f}")
            else:
                print("\nLatest closing price: N/A (data not available)")
            
            # Calculate moving average
            ma_20 = analyzer.calculate_moving_average(symbol, window=20, data=data)
            if not ma_20.empty and ma_20.iloc[-1] is not None and not pd.isna(ma_20.iloc[-1]):
                print(f"20-day Moving Average (latest): ${ma_20.iloc[-1]:.2f}")
            else:
                print(f"20-day Moving Average (latest): N/A (not enough data)")
            
            # Visualize the data
            print("\nGenerating visualization...")
            save_path = "Analytics/exports/visualizations/stock_analysis.png"
            analyzer.visualize_data(symbol, data, save_path)
        else:
            print(f"No data retrieved for {symbol}")
    
    except Exception as e:
        print(f"Error during analysis: {e}")
        import traceback
        traceback.print_exc()
    
