mkfile_path := $(abspath $(lastword $(MAKEFILE_LIST)))
current_dir := $(dir $(mkfile_path))
current_dir_name := $(notdir $(patsubst %/,%,$(current_dir)))
postgres_data_dir := $(current_dir)var/postgres/data
redis_version := 7.4.2
postgres_version := 16.8

# Detect architecture
ARCH := $(shell uname -m)
IS_ARM := $(if $(filter arm64 aarch64,$(ARCH)),true,false)

# Set image prefix based on architecture
POSTGRES_IMAGE_PREFIX := $(if $(filter true,$(IS_ARM)),arm64v8/,)
REDIS_IMAGE_PREFIX := $(if $(filter true,$(IS_ARM)),arm64v8/,)
# Set container name suffix based on architecture
POSTGRES_CONTAINER_SUFFIX := $(if $(filter true,$(IS_ARM)),_arm,)
REDIS_CONTAINER_SUFFIX := $(if $(filter true,$(IS_ARM)),_arm64v8,)

run-postgres:
	docker run -i --rm \
    -p 127.0.0.1:54329:5432 \
	--name fhir_server_postgres$(POSTGRES_CONTAINER_SUFFIX) \
	-e POSTGRES_PASSWORD=Test1234 \
	-e POSTGRES_HOST_AUTH_METHOD=trust \
	-e POSTGRES_USER=postgres \
	-e POSTGRES_DB=fhir_validator \
	-e PGDATA=/var/lib/postgresql/data/pgdata \
	-e POSTGRES_INITDB_ARGS=--auth-host=trust \
	-v $(postgres_data_dir):/var/lib/postgresql/data \
	-t $(POSTGRES_IMAGE_PREFIX)postgres:$(postgres_version)-alpine3.21 \
	-c max_wal_size=2GB

run-redis:
	docker run -i --rm \
	-p 127.0.0.1:6379:6379 \
	--name fhir_server_redis$(REDIS_CONTAINER_SUFFIX) \
	-t $(REDIS_IMAGE_PREFIX)redis:$(redis_version)

run-ollama:
	ollama serve

stop-all:
	docker kill $$(docker ps -q)