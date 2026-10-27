mkfile_path := $(abspath $(lastword $(MAKEFILE_LIST)))
current_dir := $(dir $(mkfile_path))
postgres_data_dir := $(current_dir)var/postgres/data
postgres_version := 16.8

.PHONY: up up-cluster up-obs down clean logs smoke rolling-restart run-postgres

## Start postgres + one validator (http://127.0.0.1:8880)
up:
	docker compose up -d --wait

## Start two validators behind a load balancer (http://127.0.0.1:8881)
up-cluster:
	docker compose --profile cluster up -d --wait

## Add Prometheus (http://127.0.0.1:9090) and the postgres exporter
up-obs:
	docker compose --profile observability up -d

## Stop everything, keep volumes
down:
	docker compose --profile cluster --profile observability --profile admin down

## Stop everything and delete volumes (asks first)
clean:
	@printf "This deletes the database volume. Continue? [y/N] " && read ans && [ "$${ans}" = "y" ]
	docker compose --profile cluster --profile observability --profile admin down -v

logs:
	docker compose logs -f --tail=200

## Container smoke test (build, start, validate, graceful stop)
smoke:
	sh scripts/smoke.sh

## Rolling restart of the cluster profile under k6 load; fails at >= 0.1% failed requests (SC-008)
rolling-restart:
	sh scripts/rolling-restart.sh

## PostgreSQL for IDE runs on 127.0.0.1:54329 (set FV_PG_PORT=54329 and FV_PG_PASSWORD accordingly)
run-postgres:
	@[ -n "$${FV_PG_PASSWORD}" ] || (echo "Set FV_PG_PASSWORD first" && exit 1)
	docker run -i --rm \
	-p 127.0.0.1:54329:5432 \
	--name fhir_validator_postgres \
	-e POSTGRES_PASSWORD="$${FV_PG_PASSWORD}" \
	-e POSTGRES_USER=postgres \
	-e POSTGRES_DB=fhir_validator \
	-e PGDATA=/var/lib/postgresql/data/pgdata \
	-v $(postgres_data_dir):/var/lib/postgresql/data \
	-t postgres:$(postgres_version)-alpine \
	-c max_wal_size=2GB
