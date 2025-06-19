#!/bin/sh

# Set maximum wait time to 60 seconds
MAX_WAIT=60
WAIT_COUNT=0

# Wait for PostgresSQL
echo "Waiting for PostgresSQL..."
while ! nc -z 127.0.0.1 5432; do
  sleep 1
  WAIT_COUNT=$((WAIT_COUNT+1))
  if [ $WAIT_COUNT -ge $MAX_WAIT ]; then
    echo "Timeout waiting for PostgresSQL. Continuing anyway..."
    break
  fi
done

echo "PostgresSQL is ready."
