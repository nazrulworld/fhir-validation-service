#!/bin/bash
set -e

export PGDATA=${PGDATA:-/var/lib/postgresql/16/main}

# === 1. Initialize PostgreSQL if necessary ===
if [ ! -s "$PGDATA/PG_VERSION" ]; then
    echo "Initializing PostgreSQL database at $PGDATA"
    mkdir -p "$PGDATA"
    chown -R postgres:postgres "$PGDATA"
    chmod 700 "$PGDATA"

    gosu postgres /usr/lib/postgresql/16/bin/initdb -D "$PGDATA"

    echo "host all all 0.0.0.0/0 scram-sha-256" >> "$PGDATA/pg_hba.conf"
    echo "local all all scram-sha-256" >> "$PGDATA/pg_hba.conf"
    echo "listen_addresses='*'" >> "$PGDATA/postgresql.conf"
    echo "password_encryption = scram-sha-256" >> "$PGDATA/postgresql.conf"
fi

# === 2. Start PostgreSQL ===
gosu postgres /usr/lib/postgresql/16/bin/pg_ctl -D "$PGDATA" -w start

# === 3. Run DB initialization script ===
gosu postgres /docker-entrypoint-initdb.d/init-database.sh

# === 4. Wait for dependencies if needed ===
./init-scripts/wait-for-services.sh

# === 5. Handle shutdown ===
shutdown() {
    # shellcheck disable=SC2317
    echo "Received shutdown signal. Initiating graceful shutdown..."
    # shellcheck disable=SC2317
    if [ -f /app/app.pid ]; then
        kill -TERM "$(cat /app/app.pid)" 2>/dev/null || true
    fi
    # shellcheck disable=SC2317
    gosu postgres /usr/lib/postgresql/16/bin/pg_ctl -D "$PGDATA" stop -m smart
    # shellcheck disable=SC2317
    exit 0
}
trap shutdown SIGTERM SIGINT

# === 6. Start Java app ===
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

wait -n
exit $?