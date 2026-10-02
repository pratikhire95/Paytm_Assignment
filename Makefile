# Developer shortcuts. Needs: make, Docker (with compose), a JDK 17+ for the tests, openssl for `make env`.
SHELL := /bin/bash
.DEFAULT_GOAL := help

APP_PORT ?= 8080
-include .env

.PHONY: help env up down logs ps test test-db burst burst-quick burst-one-seat clean

help: ## list the targets
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-16s %s\n", $$1, $$2}'

env: ## create .env with fresh random secrets (never overwrites an existing .env)
	@if [ -f .env ]; then \
	  echo ".env already exists - leaving it alone"; \
	else \
	  umask 077; \
	  { echo "ADMIN_TOKEN=$$(openssl rand -hex 32)"; \
	    echo "TOKEN_SECRET=$$(openssl rand -hex 48)"; \
	    echo "POSTGRES_PASSWORD=$$(openssl rand -hex 24)"; } > .env; \
	  echo "created .env with random secrets (git-ignored)"; \
	fi

up: env ## build and start the service + PostgreSQL, wait until /readyz answers 200
	docker compose up -d --build
	@echo "waiting for http://localhost:$(APP_PORT)/readyz ..."
	@for i in $$(seq 1 90); do \
	  if curl -fsS "http://localhost:$(APP_PORT)/readyz" >/dev/null 2>&1; then echo "ready: http://localhost:$(APP_PORT)"; exit 0; fi; \
	  sleep 2; \
	done; echo "not ready after 180s - look at: make logs"; exit 1

down: ## stop the containers (the database volume is kept)
	docker compose down

ps: ## container status
	docker compose ps

logs: ## follow the service logs (structured JSON, one line per event)
	docker compose logs -f --tail=200 app

test: ## unit tests only - no database needed (the Postgres-backed tests are skipped)
	cd seatManagement && ./mvnw -B -ntp test

test-db: env ## ALL tests, including the Postgres concurrency tests (starts the db container, uses database seats_test)
	docker compose up -d db
	@set -a; . ./.env; set +a; \
	user="$${POSTGRES_USER:-seats}"; \
	until docker compose exec -T db pg_isready -U "$$user" -d postgres >/dev/null 2>&1; do sleep 1; done; \
	docker compose exec -T db psql -U "$$user" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'seats_test'" | grep -q 1 \
	  || docker compose exec -T db psql -U "$$user" -d postgres -c "CREATE DATABASE seats_test"; \
	cd seatManagement && \
	TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:$${DB_HOST_PORT:-55432}/seats_test" \
	TEST_DB_USER="$$user" TEST_DB_PASSWORD="$$POSTGRES_PASSWORD" \
	./mvnw -B -ntp test

burst: ## full burst test (20,000 requests) against the local service
	./burst.sh http://localhost:$(APP_PORT)

burst-quick: ## small burst test (2,000 requests)
	./burst.sh http://localhost:$(APP_PORT) --quick

burst-one-seat: ## everyone fights over one single seat
	./burst.sh http://localhost:$(APP_PORT) --one-seat

clean: ## stop the containers AND delete the database volume
	docker compose down -v
