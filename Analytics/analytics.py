from sqlalchemy import create_engine
import pandas as pd

DB_USER = "postgres"
DB_PASSWORD = "n3u3d4!"
DB_HOST = "localhost"
DB_PORT = "15432"
DB_NAME = "capstone2026"

engine = create_engine(f"postgresql+psycopg://{DB_USER}:{DB_PASSWORD}@{DB_HOST}:{DB_PORT}/{DB_NAME}")

# TEST QUERY

tables = pd.read_sql(""" 
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                     ORDER BY table_name  
                     """, engine)

print(tables)

# with engine.connect() as conn:
#     print("bsdghsdfjsdfgjdksg")