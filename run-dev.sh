#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo "Starting Kafka..."
docker-compose -f docker-compose-kafka.yml up -d

echo "Waiting for Kafka to become healthy..."
KAFKA_HEALTH_TIMEOUT_SECONDS=120
KAFKA_HEALTH_WAIT_SECONDS=2
KAFKA_HEALTH_ELAPSED_SECONDS=0

while true; do
  KAFKA_HEALTH_STATUS="$(
    docker inspect --format '{{.State.Health.Status}}' kafka-broker 2>/dev/null || true
  )"

  if [ "$KAFKA_HEALTH_STATUS" = "healthy" ]; then
    echo "Kafka is healthy"
    break
  fi

  if [ "$KAFKA_HEALTH_STATUS" = "unhealthy" ]; then
    echo "Kafka became unhealthy" >&2
    docker-compose -f docker-compose-kafka.yml logs kafka >&2
    exit 1
  fi

  if [ "$KAFKA_HEALTH_ELAPSED_SECONDS" -ge "$KAFKA_HEALTH_TIMEOUT_SECONDS" ]; then
    echo "Kafka did not become healthy within ${KAFKA_HEALTH_TIMEOUT_SECONDS} seconds" >&2
    docker-compose -f docker-compose-kafka.yml logs kafka >&2
    exit 1
  fi

  sleep "$KAFKA_HEALTH_WAIT_SECONDS"
  KAFKA_HEALTH_ELAPSED_SECONDS=$((
    KAFKA_HEALTH_ELAPSED_SECONDS + KAFKA_HEALTH_WAIT_SECONDS
  ))
done

echo "Building application..."
mvn clean package

if [ -f ".env" ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  echo "Loaded environment from .env"
else
  echo "No .env file found; continuing with existing environment"
fi

echo "Starting Spring Boot app..."
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
exec mvn spring-boot:run
