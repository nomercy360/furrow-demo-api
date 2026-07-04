#!/usr/bin/env bash
# Tear down everything bootstrap-gcp.sh created. Node boot disks are deleted
# with the cluster; after this the project bills $0 for this setup.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-test-5b7c2}"
ZONE="${ZONE:-europe-west1-b}"
CLUSTER="${CLUSTER:-furrow-test}"

echo ">> deleting cluster ${CLUSTER} in ${ZONE} (project ${PROJECT_ID})"
gcloud container clusters delete "${CLUSTER}" \
  --project "${PROJECT_ID}" \
  --zone "${ZONE}" \
  --quiet

echo ">> done. verify nothing is left billing:"
echo "   gcloud compute disks list --project ${PROJECT_ID}"
echo "   gcloud compute forwarding-rules list --project ${PROJECT_ID}"
