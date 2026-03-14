.PHONY: run-shared run-isolated

run-shared:
	docker compose -f infra/compose/shared-storage/docker-compose.yml up -d
	sleep 5
	./gradlew :experiment-shared-storage:run
	docker compose -f infra/compose/shared-storage/docker-compose.yml down -v

run-isolated:
	docker compose -f infra/compose/isolated-storage/docker-compose.yml up -d
	sleep 5
	./gradlew :experiment-isolated-storage:run
	docker compose -f infra/compose/isolated-storage/docker-compose.yml down -v
