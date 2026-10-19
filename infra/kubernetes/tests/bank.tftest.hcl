mock_provider "helm" {}
mock_provider "kubernetes" {
  mock_data "kubernetes_namespace_v1" {
    defaults = {
      metadata = { labels = { "bank10.io/cluster" = "bank10-dev-single" } }
    }
  }
}

variables {
  environment        = "dev"
  profile            = "single"
  kubeconfig_path    = "/external/encrypted/kubeconfig"
  kube_context       = "kind-bank10-dev-single"
  project_dir        = abspath("tests/fixtures/project")
  public_values_path = abspath("tests/fixtures/single.yaml")
}

run "isolated_single_releases" {
  command = plan

  assert {
    condition     = helm_release.strimzi.version == "1.3.0" && helm_release.bank.namespace == "bank10-dev"
    error_message = "The bank must use the pinned operator in its isolated namespace."
  }
  assert {
    condition     = !helm_release.strimzi.atomic && !helm_release.bank.cleanup_on_fail && helm_release.bank.wait_for_jobs
    error_message = "A failed rollout must retain storage and finite initializer evidence."
  }
}

run "foreign_context_rejected" {
  command = plan
  variables {
    kube_context = "kind-java-sprint10"
  }
  expect_failures = [var.kube_context]
}

run "foreign_namespace_rejected" {
  command = plan
  override_data {
    target = data.kubernetes_namespace_v1.bank
    values = {
      metadata = {
        labels = { "bank10.io/cluster" = "foreign" }
      }
    }
  }
  expect_failures = [helm_release.strimzi]
}

run "superset_requires_analytics" {
  command = plan
  variables {
    superset_enabled = true
  }
  expect_failures = [var.superset_enabled]
}

run "stale_profile_values_rejected" {
  command = plan
  variables {
    analytics_enabled = true
  }
  expect_failures = [helm_release.bank]
}

run "isolated_ha_releases" {
  command = plan
  variables {
    environment        = "test"
    profile            = "ha"
    kube_context       = "kind-bank10-test-ha"
    analytics_enabled  = true
    superset_enabled   = true
    public_values_path = abspath("tests/fixtures/ha.yaml")
  }
  override_data {
    target = data.kubernetes_namespace_v1.bank
    values = {
      metadata = {
        labels = { "bank10.io/cluster" = "bank10-test-ha" }
      }
    }
  }
  assert {
    condition     = helm_release.bank.namespace == "bank10-test" && output.kafka_bootstrap == "bank-kafka-kafka-bootstrap.bank10-test.svc.cluster.local:9093"
    error_message = "HA must remain in its own namespace and authenticated listener."
  }
}
