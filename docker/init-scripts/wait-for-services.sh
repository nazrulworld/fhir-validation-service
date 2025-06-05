#!/bin/sh

# Set maximum wait time to 60 seconds
MAX_WAIT=60
WAIT_COUNT=0

# Wait for PostgreSQL
echo "Waiting for PostgreSQL..."
while ! nc -z postgres 5432; do
  sleep 1
  WAIT_COUNT=$((WAIT_COUNT+1))
  if [ $WAIT_COUNT -ge $MAX_WAIT ]; then
    echo "Timeout waiting for PostgreSQL. Continuing anyway..."
    break
  fi
done

echo "All dependencies are ready or timed out!"
