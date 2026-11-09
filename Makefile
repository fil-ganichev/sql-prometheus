.PHONY: demo-up demo-down

demo-up:
	@echo "Starting local demo environment (App, Prometheus, Grafana)..."
	docker compose -f demo/docker-compose.yml up -d --build

demo-down:
	@echo "Stopping demo environment..."
	docker compose -f demo/docker-compose.yml down
