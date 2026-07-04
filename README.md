# furrow-demo-api

A zero-dependency Java API compiled to a GraalVM native image, built to be a
**canary-rollout test subject** (for [furrow](https://github.com/nomercy360/furrow)
or any progressive-delivery tool):

- `GET /` — JSON with app name, `APP_VERSION`, and pod hostname (see which version served you)
- `GET /healthz` — liveness/readiness
- `GET /metrics` — Prometheus format: `http_requests_total{path,code,version}` + `app_info{version}`
- `FAIL_RATE=0.3` — make 30% of `/` requests return 500, so a "bad" canary
  release actually trips a Prometheus success-rate gate

No frameworks, no reflection — the native image builds with `--no-fallback --static`
and ships `FROM scratch` (~15 MB image, ~5 MB RSS at runtime).

## Build & run

```bash
mvn verify                          # tests (plain JVM, no GraalVM needed)
docker build -t furrow-demo-api .   # jar -> native image -> scratch
docker run -p 8080:8080 -e APP_VERSION=1.0 -e FAIL_RATE=0.1 furrow-demo-api
```

## CI

GitHub Actions (free on public repos): every push to `main` runs the tests and
pushes the native image to **ghcr.io** — no cloud credentials involved:

```
ghcr.io/nomercy360/furrow-demo-api:latest
ghcr.io/nomercy360/furrow-demo-api:<sha>
```

The package inherits the repo's public visibility, so GKE pulls it without
`imagePullSecrets`.

## GCP test cluster (cheapest usable setup)

Defaults to project `test-5b7c2`, zone `europe-west1-b`; override via env
(`PROJECT_ID`, `ZONE`, `CLUSTER`, `MACHINE`).

```bash
./scripts/bootstrap-gcp.sh              # zonal GKE, 1x e2-standard-2 Spot node
./scripts/bootstrap-gcp.sh --with-istio # + Istio (ClusterIP gateway) + minimal Prometheus

./scripts/teardown-gcp.sh               # delete everything
```

## GitOps layout (managed by furrow)

`gitops/` is the path furrow watches — nothing in it is applied by hand:

- `gitops/furrow.yaml` — render mode: helm-template `gitops/chart/` in-process
- `gitops/values.yaml` — `image` (stable), `canaryImage` (candidate), `canaryFail` (bad-release knob)
- `gitops/canary.yaml` — the progressive-delivery spec: bump `revision` to start a
  rollout; furrow derives `demo-api-canary` from the stable Deployment, creates the
  Istio VirtualService/DestinationRule, ramps weights, gates on Prometheus, and
  auto-rolls-back on breach. On success it commits the canary image into `image`.
- `gitops/chart/` — the Helm chart: stable Deployment, Service, and a tiny curl
  loadgen so the mesh always has request metrics to gate on.

Why this is the cheap configuration:

| Choice | Saves |
| --- | --- |
| zonal cluster | GKE free tier covers the $0.10/h management fee for one zonal cluster |
| Spot node | 60–90% off the VM price (~$15/mo for e2-standard-2, 24/7) |
| 30 GB pd-standard boot disk | vs ~$10/mo for the 100 GB pd-balanced default |
| `--logging/--monitoring SYSTEM`, managed Prometheus off | no per-GB/per-sample billing for workload telemetry |
| ClusterIP Istio gateway (use `kubectl port-forward`) | a LoadBalancer is ~$18/mo |

Pause without losing the cluster: `gcloud container clusters resize furrow-test --num-nodes 0`.
