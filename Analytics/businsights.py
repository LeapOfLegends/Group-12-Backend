from sqlalchemy import create_engine
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt
from dotenv import load_dotenv

load_dotenv()

import os

DB_USER = os.getenv("DB_USER")
DB_PASSWORD = os.getenv("DB_PASSWORD")
DB_HOST = os.getenv("DB_HOST")
DB_PORT = os.getenv("DB_PORT")
DB_NAME = os.getenv("DB_NAME")

engine = create_engine(f"postgresql+psycopg://{DB_USER}:{DB_PASSWORD}@{DB_HOST}:{DB_PORT}/{DB_NAME}")

# FUNCTIONS TO QUERY DATABASE AND EXPORT RESULTS AS CSV

#query total count of clients in the client table over each year
def get_client_counts(engine):
    client_counts_df = pd.read_sql(""" 
                     SELECT COUNT(*) AS total_new_clients, EXTRACT(YEAR FROM created_at) as year
                     FROM clients
                     GROUP BY year
                     ORDER BY year;
                     """, engine)
    print(f"Client counts shape: {client_counts_df.shape}")
    try:
        client_counts_df.to_csv("Analytics/exports/records/client_counts_by_year.csv", index=False)
        print("✓ Saved client_counts_by_year.csv")
    except Exception as e:
        print(f"✗ Error saving client_counts_by_year.csv: {e}")
    return client_counts_df

#get the most traded instruments
def get_most_traded_instruments(engine):
    most_traded_instruments_df = pd.read_sql("""
                        SELECT i.instrument_id, i.symbol, COUNT(o.order_id) AS traded_orders 
                        FROM instruments i
                        LEFT JOIN orders o
                        ON o.instrument_id = i.instrument_id
                        GROUP BY i.symbol, i.instrument_id
                        ORDER BY traded_orders DESC
                        LIMIT 10

                        ;
                        """, engine)
    print(f"Most traded instruments shape: {most_traded_instruments_df.shape}")
    try:
        most_traded_instruments_df.to_csv("Analytics/exports/records/most_traded_instruments.csv", index=False)
        print("✓ Saved most_traded_instruments.csv")
    except Exception as e:
        print(f"✗ Error saving most_traded_instruments.csv: {e}")
    return most_traded_instruments_df

#top/most expensive equity instruments by price
def get_top_instruments_by_price(engine):
    top_instruments_df = pd.read_sql("""
                              SELECT instrument_id, symbol, price 
                              FROM instruments 
                              WHERE asset_class = 'Equity'
                              ORDER BY price DESC
                              LIMIT 10;

                              """, engine)
    print(f"Top instruments by price shape: {top_instruments_df.shape}")
    try:
        top_instruments_df.to_csv("Analytics/exports/records/top_instruments_by_price.csv", index=False)
        print("✓ Saved top_instruments_by_price.csv")
    except Exception as e:
        print(f"✗ Error saving top_instruments_by_price.csv: {e}")
    return top_instruments_df

#get order status metrics (i.e. Accepted, Rejected, etc.)
def get_order_status(engine):
    order_status_df = pd.read_sql("""
                        SELECT status, COUNT(*) AS total_orders
                        FROM orders
                        GROUP BY status
                        ORDER BY total_orders DESC;
                        """, engine)
    print(f"Order status shape: {order_status_df.shape}")
    try:
        order_status_df.to_csv("Analytics/exports/records/order_status.csv", index=False)
        print("✓ Saved order_status.csv")
    except Exception as e:
        print(f"✗ Error saving order_status.csv: {e}")
    return order_status_df

#shows trades that were made from each year based on order date; only shows accepted trades
def get_trade_volume(engine):
    trade_volume_df = pd.read_sql("""
                        SELECT EXTRACT(YEAR FROM order_timestamp) AS year, COUNT(*) AS total_trades, SUM(quantity) AS shares_traded, SUM(price * quantity) AS total_value
                        FROM orders
                        WHERE status = 'ACCEPTED'
                        GROUP BY year
                        ORDER BY year;
                        """, engine)

    print(f"Trade volume shape: {trade_volume_df.shape}")
    try:
        trade_volume_df.to_csv("Analytics/exports/records/trade_volume_by_year.csv", index=False)
        print("✓ Saved trade_volume_by_year.csv")
    except Exception as e:
        print(f"✗ Error saving trade_volume_by_year.csv: {e}")
    return trade_volume_df


# *USED TO RUN QUERIES AND EXPORT RESULTS IN RECORDS FOLDER AS CSV*
#print(get_trade_volume(engine))
#get_order_status(engine)
#get_top_instruments_by_price(engine)
#get_most_traded_instruments(engine)
#get_client_counts(engine)


# *VISUALIZATIONS*

"""
# *LINE GRAPH OF CLIENT COUNTS BY YEAR*
client_by_year = get_client_counts(engine)

fig, ax = plt.subplots()
sns.lineplot(data=client_by_year, x="year", y="total_new_clients", ax=ax)
ax.set_title("Client Counts by Year")


#export lineplot (client_by_year) as pdf in exports folder
fig.savefig("Analytics/exports/visualizations/client_counts_by_year.pdf")
plt.show()
"""

"""
# *BAR PLOT OF MOST TRADED INSTRUMENTS*
most_traded_instruments = get_most_traded_instruments(engine)

fig, ax = plt.subplots()
colors = sns.color_palette("pastel")
sns.barplot(data=most_traded_instruments, x="symbol", y="traded_orders", palette=colors, ax=ax)
ax.set_ylim(0, 5)
ax.set_title("Most Traded Instruments")


##export barplot (most_traded_instruments) as pdf in exports folder
fig.savefig("Analytics/exports/visualizations/most_traded_instruments.pdf")
plt.show()
"""


# *DONUT CHART OF ORDER STATUS*
"""
order_status = get_order_status(engine)
colors = sns.color_palette("pastel")
plt.pie(order_status['total_orders'], labels=order_status['status'], autopct='%1.1f%%', startangle=90, wedgeprops=dict(width=0.5), colors=colors)
plt.title("Order Status Distribution")

#export donut chart (order_status) as pdf in exports folder
fig = plt.gcf()
fig.savefig("Analytics/exports/visualizations/order_status_distribution.pdf")
plt.show()
"""

