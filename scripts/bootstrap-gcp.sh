#!/usr/bin/env bash
# Bootstrap the cheapest usable GKE test cluster:
#   - zonal (the GKE free-tier credit covers the management fee for ONE zonal cluster)
#   - single Spot node, small standard disk
#   - system-only logging/monitoring, managed Prometheus off (both bill per sample/GB)
#
# Usage:
#   ./scripts/bootstrap-gcp.sh              # cluster only
#   ./scripts/bootstrap-gcp.sh --with-istio # + Istio and a minimal Prometheus (needs helm)
#
# Override anything via env: PROJECT_ID, ZONE, CLUSTER, MACHINE.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-test-5b7c2}"
ZONE="${ZONE:-europe-west1-b}"
CLUSTER="${CLUSTER:-furrow-test}"
MACHINE="${MACHINE:-e2-standard-2}"

echo ">> enabling container API on ${PROJECT_ID}"
gcloud services enable container.googleapis.com --project "${PROJECT_ID}"

echo ">> creating zonal cluster ${CLUSTER} (${MACHINE}, spot) in ${ZONE}"
gcloud container clusters create "${CLUSTER}" \
  --project "${PROJECT_ID}" \
  --zone "${ZONE}" \
  --num-nodes 1 \
  --machine-type "${MACHINE}" \
  --spot \
  --disk-size 30 \
  --disk-type pd-standard \
  --logging SYSTEM \
  --monitoring SYSTEM \
  --no-enable-managed-prometheus

gcloud container clusters get-credentials "${CLUSTER}" --project "${PROJECT_ID}" --zone "${ZONE}"

if [[ "${1:-}" == "--with-istio" ]]; then
  command -v helm >/dev/null || { echo "helm is required for --with-istio (brew install helm)"; exit 1; }

  echo ">> installing Istio (base + istiod + ingress gateway)"
  helm repo add istio https://istio-release.storage.googleapis.com/charts --force-update
  helm repo update istio
  helm upgrade --install istio-base istio/base -n istio-system --create-namespace --wait
  helm upgrade --install istiod istio/istiod -n istio-system \
    --set pilot.resources.requests.cpu=100m \
    --set pilot.resources.requests.memory=256Mi \
    --wait
  helm upgrade --install istio-ingress istio/gateway -n istio-system \
    --set service.type=ClusterIP \
    --set resources.requests.cpu=50m \
    --set resources.requests.memory=64Mi \
    --wait
  # service.type=ClusterIP on purpose: a LoadBalancer costs ~$18/mo.
  # Reach the gateway with: kubectl -n istio-system port-forward svc/istio-ingress 8080:80

  echo ">> installing minimal Prometheus (server only)"
  helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update
  helm repo update prometheus-community
  helm upgrade --install prometheus prometheus-community/prometheus -n monitoring --create-namespace \
    --set alertmanager.enabled=false \
    --set prometheus-pushgateway.enabled=false \
    --set prometheus-node-exporter.enabled=false \
    --set kube-state-metrics.enabled=false \
    --set server.persistentVolume.enabled=false \
    --set server.retention=6h \
    --wait
  echo ">> Prometheus URL inside the cluster: http://prometheus-server.monitoring.svc"
fi

cat <<EOF

Done. Cost-saving reminders:
  pause  : gcloud container clusters resize ${CLUSTER} --project ${PROJECT_ID} --zone ${ZONE} --num-nodes 0 --quiet
  resume : gcloud container clusters resize ${CLUSTER} --project ${PROJECT_ID} --zone ${ZONE} --num-nodes 1 --quiet
  delete : ./scripts/teardown-gcp.sh
EOF
