# Bootstrap TLS is the only Secret created before OpenBao is initialized. No Secret
# data is read by either provider; runtime credentials are fetched by workload identities.
resource "helm_release" "openbao" {
  count     = var.stack_prefix == "bank11" ? 1 : 0
  name      = "openbao"
  namespace = "${var.stack_prefix}-system"
  chart     = "oci://ghcr.io/openbao/charts/openbao@sha256:7d0ac0321c10c70d4fda3529416a33abb037d4cca01ab70c8acdf9a02b12adb8"
  version   = "0.30.4"
  timeout   = 600
  # A fresh persistent server is sealed. Initialization/unseal is a separate,
  # verified step, never a root token or unseal key in Terraform state.
  wait            = false
  atomic          = false
  cleanup_on_fail = false
  values          = var.stack_prefix == "bank11" ? [file("${var.project_dir}/openbao/values.yaml")] : []
  depends_on      = [helm_release.strimzi]

  lifecycle {
    prevent_destroy = true
  }
}

resource "helm_release" "external_secrets" {
  count           = var.stack_prefix == "bank11" ? 1 : 0
  name            = "external-secrets"
  namespace       = "${var.stack_prefix}-system"
  chart           = "oci://ghcr.io/external-secrets/charts/external-secrets@sha256:77744a13f72022d9193556df4b7e9d338ba281553a0cef31f6a8ec9924425e65"
  version         = "2.13.0"
  timeout         = 600
  wait            = true
  atomic          = false
  cleanup_on_fail = false
  values = [yamlencode({
    installCRDs     = true
    scopedRBAC      = true
    scopedNamespace = local.namespace
    resources = {
      requests = { cpu = "20m", memory = "64Mi" }
      limits   = { cpu = "300m", memory = "160Mi" }
    }
    webhook = { resources = {
      requests = { cpu = "10m", memory = "32Mi" }
      limits   = { cpu = "200m", memory = "96Mi" }
    } }
    certController = { resources = {
      requests = { cpu = "10m", memory = "32Mi" }
      limits   = { cpu = "200m", memory = "96Mi" }
    } }
  })]
  depends_on = [helm_release.strimzi]

  lifecycle {
    prevent_destroy = true
  }
}
