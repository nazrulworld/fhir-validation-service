#!/bin/bash
set -e

# Start PostgresSQL
gosu postgres /usr/lib/postgresql/16/bin/pg_ctl -D "$PGDATA" start
gosu postgres /docker-entrypoint-initdb.d/init-database.sh
./init-scripts/wait-for-services.sh

# Function to handle shutdown
shutdown() {
    # shellcheck disable=SC2317
    echo "Received shutdown signal. Initiating graceful shutdown..."
    # Stop Java application (send SIGTERM to the Java process)
    # shellcheck disable=SC2317
    if [ -f /app/app.pid ]; then
        # shellcheck disable=SC2046
        kill -TERM $(cat /app/app.pid) 2>/dev/null || true
    fi
    
    # Stop PostgresSQL gracefully
    # shellcheck disable=SC2317
    gosu postgres /usr/lib/postgresql/16/bin/pg_ctl -D "$PGDATA" stop -m smart
    # shellcheck disable=SC2317
    exit 0
}

# Setup signal handling
trap shutdown SIGTERM SIGINT

# Start Java application in the background
gosu fhiruser java \
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -Djava.security.egd=file:/dev/./urandom \
    -Dvertx.environment=docker \
    -Dapplication.config.path=/app/application-docker.properties \
    -Dvertx.config.path="$CONFIG_PATH" \
    -Dvertx.preferNativeTransport=true \
    -Dvertx.disableDnsResolver=false \
    -Dvertx.addressResolverOptions.servers=["1.1.1.1","8.8.4.4"] \
    -jar /app/app.jar & echo $! > /app/app.pid

# Wait for any process to exit
wait -n

# Exit with status of process that exited first
exit $?