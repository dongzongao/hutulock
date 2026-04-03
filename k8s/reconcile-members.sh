#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="${NAMESPACE:-hutulock}"
STATEFULSET="${STATEFULSET:-hutulock}"
HEADLESS_SERVICE="${HEADLESS_SERVICE:-hutulock-raft}"
ADMIN_SERVICE="${ADMIN_SERVICE:-hutulock-admin-api}"
BOOTSTRAP_REPLICAS="${BOOTSTRAP_REPLICAS:-3}"
TARGET_REPLICAS="${TARGET_REPLICAS:-}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-15}"
RUN_ONCE="${RUN_ONCE:-false}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[ERROR] missing required command: $1" >&2
    exit 1
  fi
}

require_cmd kubectl
require_cmd curl
require_cmd jq

login_token() {
  curl -fsS -X POST "http://${ADMIN_SERVICE}.${NAMESPACE}.svc.cluster.local:9091/api/admin/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${ADMIN_USERNAME}\",\"password\":\"${ADMIN_PASSWORD}\"}" \
    | jq -r '.token'
}

cluster_state() {
  local token="$1"
  curl -fsS "http://${ADMIN_SERVICE}.${NAMESPACE}.svc.cluster.local:9091/api/admin/cluster" \
    -H "Authorization: Bearer ${token}"
}

leader_admin_url() {
  local cluster_json="$1"
  local leader_id
  leader_id="$(printf '%s' "${cluster_json}" | jq -r '.leaderId')"
  if [ -z "${leader_id}" ] || [ "${leader_id}" = "null" ]; then
    return 1
  fi
  local ordinal="${leader_id#node}"
  ordinal="$((ordinal - 1))"
  printf 'http://%s-%s.%s.%s.svc.cluster.local:9091' \
    "${STATEFULSET}" "${ordinal}" "${HEADLESS_SERVICE}" "${NAMESPACE}"
}

wait_membership_idle() {
  local token="$1"
  local attempts=40
  while [ "${attempts}" -gt 0 ]; do
    local cluster_json
    cluster_json="$(cluster_state "${token}")"
    if [ "$(printf '%s' "${cluster_json}" | jq -r '.membershipChangePending')" = "false" ]; then
      return 0
    fi
    attempts="$((attempts - 1))"
    sleep 3
  done
  echo "[ERROR] membership change did not settle in time" >&2
  return 1
}

patch_statefulset_replicas() {
  local replicas="$1"
  kubectl -n "${NAMESPACE}" patch statefulset "${STATEFULSET}" \
    --type merge \
    -p "{\"spec\":{\"replicas\":${replicas}}}" >/dev/null
}

wait_pod_ready() {
  local pod_name="$1"
  kubectl -n "${NAMESPACE}" wait --for=condition=Ready "pod/${pod_name}" --timeout=180s >/dev/null
}

member_host() {
  local ordinal="$1"
  printf '%s-%s.%s.%s.svc.cluster.local' \
    "${STATEFULSET}" "${ordinal}" "${HEADLESS_SERVICE}" "${NAMESPACE}"
}

add_member() {
  local token="$1"
  local node_id="$2"
  local ordinal="$3"
  local leader_url="$4"

  curl -fsS -X POST "${leader_url}/api/admin/members/add" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"nodeId\":\"${node_id}\",\"host\":\"$(member_host "${ordinal}")\",\"port\":9881}" >/dev/null
}

remove_member() {
  local token="$1"
  local node_id="$2"
  local leader_url="$3"

  curl -fsS -X POST "${leader_url}/api/admin/members/remove" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"nodeId\":\"${node_id}\"}" >/dev/null
}

current_member_count() {
  local cluster_json="$1"
  printf '%s' "${cluster_json}" | jq '.members | length'
}

desired_replicas() {
  if [ -n "${TARGET_REPLICAS}" ]; then
    printf '%s' "${TARGET_REPLICAS}"
    return
  fi
  kubectl -n "${NAMESPACE}" get statefulset "${STATEFULSET}" -o jsonpath='{.spec.replicas}'
}

reconcile_once() {
  local desired token cluster_json leader_url current
  desired="$(desired_replicas)"
  token="$(login_token)"
  cluster_json="$(cluster_state "${token}")"
  leader_url="$(leader_admin_url "${cluster_json}")"
  current="$(current_member_count "${cluster_json}")"

  if [ "${desired}" -gt "${current}" ]; then
    patch_statefulset_replicas "${desired}"
    local ordinal
    ordinal="${current}"
    while [ "${ordinal}" -lt "${desired}" ]; do
      local pod_name="${STATEFULSET}-${ordinal}"
      local node_id="node$((ordinal + 1))"
      wait_pod_ready "${pod_name}"
      add_member "${token}" "${node_id}" "${ordinal}" "${leader_url}"
      wait_membership_idle "${token}"
      cluster_json="$(cluster_state "${token}")"
      leader_url="$(leader_admin_url "${cluster_json}")"
      ordinal="$((ordinal + 1))"
    done
    return
  fi

  if [ "${desired}" -lt "${current}" ]; then
    local ordinal="$((current - 1))"
    while [ "${ordinal}" -ge "${desired}" ]; do
      local node_id="node$((ordinal + 1))"
      remove_member "${token}" "${node_id}" "${leader_url}"
      wait_membership_idle "${token}"
      cluster_json="$(cluster_state "${token}")"
      leader_url="$(leader_admin_url "${cluster_json}")"
      ordinal="$((ordinal - 1))"
    done
    patch_statefulset_replicas "${desired}"
    return
  fi

  echo "[INFO] statefulset replicas and raft members already match: ${desired}"
}

main() {
  while true; do
    reconcile_once
    if [ "${RUN_ONCE}" = "true" ]; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SEC}"
  done
}

main "$@"
