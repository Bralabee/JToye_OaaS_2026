#!/bin/bash
# Run the Spring Boot application with correct database configuration

export DB_PORT=5433
export DB_USER=jtoye
export DB_PASSWORD=secret
export DB_NAME=jtoye
export DB_HOST=localhost

echo "Starting JToye OaaS application..."
echo "Database: postgresql://localhost:5433/jtoye"
echo "Server will start on: http://localhost:9090"
echo ""

cd "$(dirname "$0")/core-java"
../gradlew bootRun
