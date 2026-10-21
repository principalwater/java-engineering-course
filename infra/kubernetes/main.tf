locals {
  namespace      = "${var.stack_prefix}-${var.environment}"
  cluster        = "${var.stack_prefix}-${var.environment}-${var.profile}"
  public_values  = yamldecode(file(var.public_values_path))
  operator_image = "sha256:4deb564fa329afa4046c550a6f8f1a6d00be3b9297e556969acabe6647b6676f"
}

data "kubernetes_namespace_v1" "bank" {
  metadata {
    name = local.namespace
  }
}

resource "helm_release" "cilium" {
  count           = var.stack_prefix == "bank11" ? 1 : 0
  name            = "cilium"
  namespace       = "kube-system"
  chart           = "oci://quay.io/cilium/charts/cilium@sha256:e84f73cc9edcb0d00306ecee57a56f20bb32dddad77c8cef3af62d844ec8adea"
  version         = "1.20.3"
  timeout         = 600
  wait            = true
  atomic          = false
  cleanup_on_fail = false

  values = [yamlencode({
    ipam                 = { mode = "kubernetes" }
    ipv4                 = { enabled = true }
    ipv6                 = { enabled = true }
    k8s                  = { requireIPv4PodCIDR = true, requireIPv6PodCIDR = true }
    kubeProxyReplacement = "false"
    operator = {
      replicas = 1
      resources = {
        requests = { cpu = "50m", memory = "96Mi" }
        limits   = { cpu = "500m", memory = "256Mi" }
      }
    }
    resources = {
      requests = { cpu = "100m", memory = "192Mi" }
      limits   = { cpu = "1", memory = "512Mi" }
    }
  })]

  lifecycle {
    prevent_destroy = true
    precondition {
      condition     = lookup(data.kubernetes_namespace_v1.bank.metadata[0].labels, "${var.stack_prefix}.io/cluster", "") == local.cluster
      error_message = "Cilium must belong to the explicitly bootstrapped isolated bank cluster."
    }
  }
}

resource "helm_release" "strimzi" {
  name             = "${var.stack_prefix}-strimzi"
  namespace        = "${var.stack_prefix}-system"
  create_namespace = true
  chart            = "oci://quay.io/strimzi-helm/strimzi-kafka-operator@sha256:08852fb5aca9782750ec1698616cad0efe15d5eef1a366d124373129ff9c29ea"
  version          = "1.3.0"
  timeout          = 600
  wait             = true
  atomic           = false
  cleanup_on_fail  = false

  values = [yamlencode({
    watchNamespaces             = [local.namespace]
    watchAnyNamespace           = false
    createAggregateRoles        = false
    generateNetworkPolicy       = false
    generatePodDisruptionBudget = true
    image                       = { digest = local.operator_image }
    topicOperator               = { image = { digest = local.operator_image } }
    userOperator                = { image = { digest = local.operator_image } }
    kafkaInit                   = { image = { digest = local.operator_image } }
    resources = {
      requests = { cpu = "100m", memory = "384Mi" }
      limits   = { cpu = "1", memory = "384Mi" }
    }
  })]

  depends_on = [helm_release.cilium]

  lifecycle {
    prevent_destroy = true
    precondition {
      condition     = lookup(data.kubernetes_namespace_v1.bank.metadata[0].labels, "${var.stack_prefix}.io/cluster", "") == local.cluster
      error_message = "Namespace ownership must be established by the bank Kubernetes bootstrap."
    }
  }
}

resource "helm_release" "bank" {
  name            = "bank"
  namespace       = local.namespace
  chart           = "${var.project_dir}/deployment/charts/bank"
  timeout         = 1200
  wait            = true
  wait_for_jobs   = true
  atomic          = false
  cleanup_on_fail = false

  values = [file(var.public_values_path), yamlencode({
    global   = { environment = var.environment, analyticsEnabled = var.analytics_enabled }
    kafka    = { profile = var.profile, analyticsEnabled = var.analytics_enabled }
    superset = { enabled = var.superset_enabled }
  })]

  depends_on = [helm_release.strimzi, helm_release.openbao, helm_release.external_secrets]

  lifecycle {
    prevent_destroy = true
    precondition {
      condition = (
        local.public_values.global.environment == var.environment &&
        local.public_values.kafka.profile == var.profile &&
        local.public_values.global.analyticsEnabled == var.analytics_enabled &&
        local.public_values.superset.enabled == var.superset_enabled
      )
      error_message = "Prepared values belong to a different environment/profile/scenario."
    }
  }
}
