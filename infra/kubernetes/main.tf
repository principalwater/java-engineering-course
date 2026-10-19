locals {
  namespace      = "bank10-${var.environment}"
  cluster        = "bank10-${var.environment}-${var.profile}"
  public_values  = yamldecode(file(var.public_values_path))
  operator_image = "sha256:4deb564fa329afa4046c550a6f8f1a6d00be3b9297e556969acabe6647b6676f"
}

data "kubernetes_namespace_v1" "bank" {
  metadata {
    name = local.namespace
  }
}

resource "helm_release" "strimzi" {
  name             = "bank10-strimzi"
  namespace        = "bank10-system"
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

  lifecycle {
    prevent_destroy = true
    precondition {
      condition     = lookup(data.kubernetes_namespace_v1.bank.metadata[0].labels, "bank10.io/cluster", "") == local.cluster
      error_message = "Namespace ownership must be established by the bank Kubernetes bootstrap."
    }
  }
}

resource "helm_release" "bank" {
  name            = "bank"
  namespace       = local.namespace
  chart           = "${var.project_dir}/charts/bank"
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

  depends_on = [helm_release.strimzi]

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
