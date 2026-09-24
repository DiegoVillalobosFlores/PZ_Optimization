#!/usr/bin/env bash
# The public dashboard on GCP (project diegov), built for near-zero cost, at https://pzo.diegov.dev:
#   pzopt-db          e2-micro VM in us-central1 (always-free tier, US regions only), 30 GB pd-standard, Postgres 15, NO external IP; the desktop reaches
#                     it through IAP (user unit pzopt-grafana-tunnel: localhost:15433), the follower (ingest.py) writes
#                     every import and live sample there too (~/.config/pzopt/grafana-remote.env, spool on failure)
#   pzopt-dashboard   Cloud Run service in europe-west1: Grafana (harness/grafana/Dockerfile), anonymous read-only,
#                     scale to zero, max 2 instances, request-based CPU; reaches the VM's internal IP over Direct VPC
#                     egress (same global VPC, the europe-west1 subnet is inside pg_hba's 10.128.0.0/9)
#   pzopt             Artifact Registry repo in europe-west1, the 2 newest images kept (free 0.5 GB)
#
#   harness/grafana/cloud.sh deploy     # build the image on Cloud Build (free tier) and deploy it; prints the URL
#   harness/grafana/cloud.sh url        # the public URL
#   harness/grafana/cloud.sh domain     # map pzo.diegov.dev to the service (DNS: CNAME pzo -> ghs.googlehosted.com on
#                                       # Cloudflare, DNS only / grey cloud, or Google's certificate never issues)
#   harness/grafana/cloud.sh status     # service, VM, tunnel, spool, remote DB size
#   harness/grafana/cloud.sh seed       # empty the remote DB and copy the local one over (after a schema change)
#   harness/grafana/cloud.sh tunnel     # (re)install the desktop's IAP tunnel unit
# One-time setup done on 2026-09-24 by hand (see harness/CLAUDE.md "Public dashboard"): the VM + Postgres, the firewall
# rule, the passwords in ~/.config/pzopt/grafana-remote.env, the EUR 5 budget alert on label app=pzopt-dashboard.
#   harness/grafana/cloud.sh ssh        # a shell on the VM (IAP)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT=diegov
REGION=europe-west1  # Cloud Run + Artifact Registry (domain mappings exist in europe-west1)
ZONE=us-central1-a   # the VM
DOMAIN=pzo.diegov.dev
SERVICE=pzopt-dashboard
REPO=pzopt
IMAGE="$REGION-docker.pkg.dev/$PROJECT/$REPO/grafana"
SA="pzopt-dashboard@$PROJECT.iam.gserviceaccount.com"
ENVF="$HOME/.config/pzopt/grafana-remote.env"
# shellcheck disable=SC1090
. "$ENVF"
g() { gcloud --project "$PROJECT" --quiet "$@"; }

remote_psql() {
  PGHOST=$PZ_REMOTE_PGHOST PGPORT=$PZ_REMOTE_PGPORT PGUSER=$PZ_REMOTE_PGUSER PGPASSWORD=$PZ_REMOTE_PGPASSWORD \
    PGDATABASE=$PZ_REMOTE_PGDATABASE PGSSLMODE=disable psql -X "$@"
}

ensure_infra() {
  if ! g artifacts repositories describe "$REPO" --location "$REGION" >/dev/null 2>&1; then
    g artifacts repositories create "$REPO" --location "$REGION" --repository-format docker \
      --description "PZ Optimization public dashboard images"
    cat >/tmp/pzopt-ar-policy.json <<'EOF'
[{"name": "keep-2-newest", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 2}},
 {"name": "delete-rest", "action": {"type": "Delete"}, "condition": {"tagState": "any"}}]
EOF
    g artifacts repositories set-cleanup-policies "$REPO" --location "$REGION" --policy /tmp/pzopt-ar-policy.json --no-dry-run
    rm -f /tmp/pzopt-ar-policy.json
  fi
  if ! g iam service-accounts describe "$SA" >/dev/null 2>&1; then
    # no roles: Grafana only talks to Postgres over the VPC
    g iam service-accounts create pzopt-dashboard --display-name "PZ Optimization public dashboard (Cloud Run, no roles)"
  fi
}

deploy() {
  ensure_infra
  python3 "$HERE/dashboards.py"
  local tag
  tag="$IMAGE:$(date +%Y%m%d-%H%M%S)"
  g builds submit "$HERE" --tag "$tag" --timeout 900s  # default pool + machine = the free build minutes
  g run deploy "$SERVICE" --image "$tag" --region "$REGION" --port 3000 \
    --service-account "$SA" --allow-unauthenticated \
    --min-instances 0 --max-instances 2 --concurrency 80 --cpu 1 --memory 512Mi --cpu-throttling --cpu-boost \
    --timeout 60 --network default --subnet default --vpc-egress private-ranges-only \
    --set-env-vars "PZ_DB_HOST=$PZ_REMOTE_DB_INTERNAL_IP,PZ_DB_PASSWORD=$PZ_REMOTE_GRAFANA_PASSWORD,GF_SECURITY_ADMIN_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -d '/+='),GF_SERVER_ROOT_URL=%(protocol)s://%(domain)s/" \
    --labels app=pzopt-dashboard
  url
}

# the desktop's way in: IAP TCP forwarding (free; firewall pzopt-db-allow-iap-pg lets 35.235.240.0/20 reach 5432)
tunnel() {
  local dir="$HOME/.config/systemd/user"
  mkdir -p "$dir"
  cat >"$dir/pzopt-grafana-tunnel.service" <<UNITEOF
[Unit]
Description=PZ Optimization metrics: IAP tunnel to the cloud dashboard's Postgres (localhost:$PZ_REMOTE_PGPORT -> pzopt-db:5432)

[Service]
ExecStart=$(command -v gcloud) compute start-iap-tunnel pzopt-db 5432 --local-host-port=localhost:$PZ_REMOTE_PGPORT --zone $ZONE --project $PROJECT
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
UNITEOF
  systemctl --user daemon-reload
  systemctl --user enable --now pzopt-grafana-tunnel
}

url() {
  echo "https://$DOMAIN"
  g run services describe "$SERVICE" --region "$REGION" --format 'value(status.url)'
}

# one-time: diegov.dev is verified for the account (gcloud domains list-user-verified); the certificate follows the DNS record
domain() {
  g beta run domain-mappings describe --domain "$DOMAIN" --region "$REGION" >/dev/null 2>&1 ||
    g beta run domain-mappings create --service "$SERVICE" --domain "$DOMAIN" --region "$REGION"
  g beta run domain-mappings describe --domain "$DOMAIN" --region "$REGION" \
    --format 'table(status.resourceRecords[].name,status.resourceRecords[].type,status.resourceRecords[].rrdata,status.conditions[].type,status.conditions[].status)'
}

status() {
  g run services describe "$SERVICE" --region "$REGION" --format 'value(status.url,status.latestReadyRevisionName)' || true
  g beta run domain-mappings describe --domain "$DOMAIN" --region "$REGION" --format 'value(metadata.name,status.conditions[0].status)' || true
  g compute instances describe pzopt-db --zone "$ZONE" --format 'value(status,networkInterfaces[0].networkIP)'
  systemctl --user is-active pzopt-grafana-tunnel | sed 's/^/tunnel: /'
  echo "spooled scripts: $(ls "${XDG_STATE_HOME:-$HOME/.local/state}/pzopt-grafana/spool" 2>/dev/null | wc -l)"
  remote_psql -At -c "SELECT 'remote runs ' || count(*) || ', ' || pg_size_pretty(pg_database_size('pzopt')) FROM runs"
}

seed() {
  systemctl --user stop pzopt-grafana-follow
  remote_psql -q -v ON_ERROR_STOP=1 -f "$HERE/schema.sql"
  remote_psql -q -c "DO \$\$ DECLARE r record; BEGIN FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP EXECUTE format('TRUNCATE %I', r.tablename); END LOOP; END \$\$;"
  rm -f "${XDG_STATE_HOME:-$HOME/.local/state}"/pzopt-grafana/spool/*.sql
  podman exec pzopt-grafana-pg pg_dump -U pzopt --data-only --no-owner pzopt | remote_psql -q -v ON_ERROR_STOP=1
  systemctl --user start pzopt-grafana-follow
  status
}

case "${1:-status}" in
  deploy) deploy ;;
  url) url ;;
  domain) domain ;;
  status) status ;;
  seed) seed ;;
  tunnel) tunnel ;;
  ssh) exec gcloud compute ssh pzopt-db --zone "$ZONE" --project "$PROJECT" --tunnel-through-iap ;;
  *) sed -n 2,23p "$0"; exit 2 ;;
esac
