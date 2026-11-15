ifeq ($(OS),Windows_NT)
GRADLE = gradlew.bat
else
GRADLE = ./gradlew
endif

.PHONY: demo-up demo-down benchmark

demo-up:
	@echo "Starting local demo environment (App, Prometheus, Grafana)..."
	docker compose -f demo/docker-compose.yml up -d --build

demo-down:
	@echo "Stopping demo environment..."
	docker compose -f demo/docker-compose.yml down

benchmark:
	@echo "Stopping the demo so one Prometheus process can open the TSDB..."
	docker compose -f demo/docker-compose.yml stop
	py -3 benchmarks/require_tsdb.py
	docker compose -f demo/docker-compose.yml up -d prometheus
	$(GRADLE) :benchmarks:benchmark
