FROM python:3.12-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py ./
# Runs the pipeline at 4:00 PM ET on weekdays; run once with: docker exec capstone-etl python main.py
CMD ["python", "schedule.py"]
