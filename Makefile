# Shortcuts. Everything also works without make; see README.

CLUSTER ?= queryguard
TAG     ?= dev

.PHONY: build test it up down demo traffic dashboards bench-pgbench k8s-up k8s-down chart-sync

build:
	./gradlew build -x integrationTest

test:
	./gradlew test

it:
	./gradlew integrationTest

up:
	docker compose up -d --build --wait

down:
	docker compose down -v

demo:
	docker run --rm --network queryguard_default -v "$(PWD)/infra/demo:/demo" postgres:16 \
	  psql -X -v qg_host=proxy "postgresql://postgres:dev@proxy:6432/shop" -f /demo/rules.sql

traffic:
	TRAFFIC_RATE=400 TRAFFIC_WORKER_SCALE=3 docker compose --profile traffic up -d --build --no-deps traffic

dashboards:
	python analytics/grafana/build_dashboards.py

bench-pgbench:
	docker run --rm --network queryguard_default -e PGPASSWORD=dev -v "$(PWD)/bench/pgbench:/bench" \
	  postgres:16 sh /bench/run.sh

# The chart can't read files outside its own directory, so the default rules are copied in.
chart-sync:
	cp infra/rules/default-rules.yml infra/helm/queryguard/files/default-rules.yml

# Local Kubernetes: kind cluster, demo Postgres (Bitnami chart, seeded from infra/seed),
# QueryGuard from locally built images, then a smoke test through the proxy Service.
k8s-up: chart-sync
	kind create cluster --name $(CLUSTER) || true
	docker build -t ghcr.io/thotaarchitha/queryguard-proxy:$(TAG) -f proxy/Dockerfile .
	docker build -t ghcr.io/thotaarchitha/queryguard-admin-api:$(TAG) -f admin-api/Dockerfile .
	kind load docker-image --name $(CLUSTER) ghcr.io/thotaarchitha/queryguard-proxy:$(TAG) ghcr.io/thotaarchitha/queryguard-admin-api:$(TAG)
	kubectl create configmap qg-seed --from-file=infra/seed --dry-run=client -o yaml | kubectl apply -f -
	helm repo add bitnami https://charts.bitnami.com/bitnami >/dev/null 2>&1 || true
	helm upgrade --install qg-postgres bitnami/postgresql --wait --timeout 10m \
	  --set auth.postgresPassword=dev --set auth.database=shop \
	  --set primary.initdb.scriptsConfigMap=qg-seed
	helm upgrade --install qg infra/helm/queryguard --wait --timeout 5m \
	  --set image.tag=$(TAG) --set image.pullPolicy=Never
	kubectl run qg-smoke --rm -i --restart=Never --image=postgres:16 -- \
	  psql "postgresql://bob:bob@qg-proxy:6432/shop" -c "DELETE FROM orders" 2>&1 | grep -q "QueryGuard R1" \
	  && echo "smoke test passed: R1 blocked through the proxy Service" \
	  || (echo "smoke test FAILED" && exit 1)

k8s-down:
	kind delete cluster --name $(CLUSTER)
