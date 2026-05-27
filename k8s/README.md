# fm-social Kubernetes Manifests

Matches the `main` service deployment pattern (single `SPRING_PROFILES_ACTIVE=k8s` env var,
non-credential config in `application-k8s.yml`, secrets injected from K8s). DB credentials are
sourced from `postgres-secret` via env vars (no longer hardcoded) so the k8s profile fails fast
if the secret isn't injected.

## Files

| File | Purpose | Resource name |
|------|---------|---------------|
| `deployment.yaml` | Deployment — 1 replica, port 80, RollingUpdate 25/25 | `social` |
| `service.yaml` | ClusterIP service on port 80 | `social` |
| `ingress.yaml` | nginx ingress at `fm-social.finmates.com` | `social-ingress` |

Note: the Ingress is named `social-ingress` (to distinguish ingress resources from same-named
services/deployments in `kubectl get all`). The Deployment and Service are both named `social`.
The backend service reference inside ingress.yaml points to the Service `social` — this is correct.

## Apply Order

```bash
kubectl apply -f k8s/service.yaml -n dev
kubectl apply -f k8s/deployment.yaml -n dev
kubectl apply -f k8s/ingress.yaml -n dev
```

Or apply all at once (safe — no order dependency for these resources):

```bash
kubectl apply -f k8s/ -n dev
```

## Manual Deploy

```bash
# Full redeploy (image already pushed to Nexus)
kubectl apply -f k8s/ -n dev
kubectl rollout restart deployment/social -n dev
kubectl rollout status deployment/social -n dev
```

## URLs

| Environment | URL |
|-------------|-----|
| Kubernetes (dev) | https://fm-social.finmates.com |
| Local dev | http://localhost:8091 |
| Swagger (local) | http://localhost:8091/swagger-ui.html |
| Swagger (k8s) | https://fm-social.finmates.com/swagger-ui.html |

## Profile & Port

| Profile | Port | DB | Notes |
|---------|------|----|-------|
| `dev` | 8091 | finmates.com:5432/social | Local dev |
| `k8s` | 80 | postgres.dev.svc.cluster.local:5432/social | K8s (dev namespace) |

## Required Secrets in `dev` Namespace

| Secret | Key | Used for |
|--------|-----|----------|
| `fm-internal-secret` | `internal-shared-secret` | `INTERNAL_SHARED_SECRET` env var |
| `postgres-secret` | `POSTGRES_USER`, `POSTGRES_PASSWORD` | Social DB credentials (existing — shared in-cluster Postgres secret; injected via `secretKeyRef` so the k8s profile fails fast if absent rather than falling back to a hardcoded password) |

Verify the secrets exist:
```bash
kubectl get secret fm-internal-secret postgres-secret -n dev
```

## REPLACE_AT_BUILD Placeholder

`application-k8s.yml` contains one placeholder that must be filled before the first deploy:

```
spring.data.redis.password: REPLACE_AT_BUILD
```

Set this to the value from K8s Secret `redis-auth`, key `password`:
```bash
kubectl get secret redis-auth -n dev -o jsonpath='{.data.password}' | base64 -d
```

Edit `application-k8s.yml` with the actual value, rebuild the Docker image, and push
to Nexus before running `kubectl apply`.

## Image

```
10.0.0.70:8090/fm-social:latest
```

Built by Jenkins pipeline → pushed to Nexus registry → pulled by K8s on rollout.

## DNS

Add an A record (or CNAME) for `fm-social.finmates.com` pointing to the nginx ingress
controller's external IP (same IP as the other `*.finmates.com` services).
