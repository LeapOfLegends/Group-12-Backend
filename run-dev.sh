#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [ -f ".env" ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  echo "Loaded environment from .env"
else
  echo "No .env file found; continuing with existing environment"
fi

echo "Starting Kafka..."
docker-compose -f docker-compose-kafka.yml up -d

echo "Building application..."
mvn clean package

echo "Starting Spring Boot app..."
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
exec mvn spring-boot:run
