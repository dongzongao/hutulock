#!/usr/bin/env bash
# HutuLock K8s 一键部署脚本
# 用法：
#   ./k8s/deploy.sh              # 部署
#   ./k8s/deploy.sh delete       # 删除所有资源

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ACTION="${1:-apply}"

if [ "$ACTION" = "delete" ]; then
    kubectl delete -f "${DIR}/" --ignore-not-found
    echo "All HutuLock resources deleted."
    exit 0
fi

echo "==> Deploying HutuLock to Kubernetes..."

kubectl apply -f "${DIR}/namespace.yaml"
kubectl apply -f "${DIR}/configmap.yaml"
kubectl apply -f "${DIR}/services.yaml"
kubectl apply -f "${DIR}/statefulset.yaml"
kubectl apply -f "${DIR}/admin-deployment.yaml"
kubectl apply -f "${DIR}/ingress.yaml"

echo ""
echo "==> Waiting for StatefulSet to be ready..."
kubectl rollout status statefulset/hutulock -n hutulock --timeout=120s

echo ""
echo "==> HutuLock deployed successfully!"
echo ""
echo "Pods:"
kubectl get pods -n hutulock
echo ""
echo "Services:"
kubectl get svc -n hutulock
