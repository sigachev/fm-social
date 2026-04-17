# fm-social

Unified social microservice for FinMates. Handles posts, polymorphic comments, crypto-native
reactions, profile presentation, follow graph, blocks, moderation, and content reports.

**Port:** 8091 

## Prerequisites

- Java 21
- PostgreSQL 14+ with the `social` database created (see below)
- Redis 7 (in Kubernetes dev namespace, or locally via Docker)

## Database Setup

Run as the `postgres` superuser on `finmates.com:5432` before the first service start:

```sql
CREATE DATABASE social;
GRANT ALL PRIVILEGES ON DATABASE social TO finmates;
```

Flyway applies all migrations automatically on startup. Do not run SQL files manually.

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DB_USERNAME` | No | `finmates` | PostgreSQL username |
| `DB_PASSWORD` | Yes | — | PostgreSQL password |
| `REDIS_HOST` | No | `localhost` | Redis hostname |
| `REDIS_PORT` | No | `6379` | Redis port |
| `REDIS_PASSWORD` | Yes | — | Redis AUTH password (from `redis-auth` secret) |
| `INTERNAL_SHARED_SECRET` | Yes | — | Shared secret for service-to-service calls (`X-Internal-Secret` header) |
| `SPRING_PROFILES_ACTIVE` | No | `dev` | Active Spring profile (`dev` or `prod`) |
| `SERVER_PORT` | No | `8091` | Override HTTP port |
| `JAVA_OPTS` | No | `""` | JVM flags passed to the runtime jar |

**Retrieving the Redis password from Kubernetes:**
```bash
kubectl get secret redis-auth -n dev -o jsonpath='{.data.password}' | base64 -d
```

**Port-forwarding Redis for local dev:**
```bash
kubectl port-forward -n dev svc/redis 6379:6379
```

## Running Locally

```bash
# Windows
.\mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

Set env vars in your shell or IDE run configuration before starting.

## API Documentation

Swagger UI: `http://localhost:8091/swagger-ui.html`
OpenAPI JSON: `http://localhost:8091/v3/api-docs`

All endpoints except `/actuator/health`, `/actuator/info`, and `/api/profiles/*/public`
require a valid Keycloak Bearer token in the `Authorization` header.

## Build

```bash
# Compile only
.\mvnw.cmd clean compile

# Build fat jar (skips tests)
.\mvnw.cmd clean package -DskipTests

# Build + test
.\mvnw.cmd clean package
```

## Docker

```bash
# Build image locally
docker build -t fm-social:local .

# Run (pass env vars)
docker run -p 8091:8091 \
  -e DB_PASSWORD=... \
  -e REDIS_PASSWORD=... \
  -e INTERNAL_SHARED_SECRET=... \
  fm-social:local
```

## Password Rotation

To rotate the `INTERNAL_SHARED_SECRET`, update it in all services simultaneously
(finmates-main, finmates-crypto, fm-social) to avoid service-to-service auth failures.

To rotate the Redis password, see the Redis README at `F:\Projects\finmates-k8s\redis\README.md`.
