mock_provider "docker" {}

variables {
  project_directory = abspath("tests/fixtures/bank-project")
  runtime_directory = abspath("tests/fixtures/bank-project")
  stack_name        = "java-tofu-sprint09"
  secrets_directory = "/tmp/external-bank-secret-source"
  runtime_user      = "10001:10001"
  artifact_hashes = {
    accounts      = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    cash          = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    transfer      = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    notifications = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    front         = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    gateway       = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    analytics     = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}

run "core_native_graph_is_isolated_and_analytics_storage_is_stable" {
  command = plan
  module { source = "./modules/bank" }
  assert {
    condition = length(docker_container.application) == 6 && length(docker_container.platform) == 2 && length(docker_container.authorization) == 1 && length(docker_container.initializer) == 1 && length(docker_volume.data) == 6 && (
      output.deployment.frontend_url == "https://bank.valgrind-home.localhost:28450" &&
      output.deployment.issuer == "https://auth-bank.valgrind-home.localhost:28450/realms/bank"
    )
    error_message = "Core startup must contain six apps with isolated TLS and retain all independent persistent storage."
  }
  assert {
    condition = alltrue([for name, container in docker_container.application : (
      container.read_only && container.memory == container.memory_swap && container.memory > 0 &&
      length(container.ports) == 0 && length(container.healthcheck) == 0 &&
      alltrue([for mount in container.volumes : mount.read_only && startswith(mount.host_path, "/tmp/external-bank-secret-source/")])
    )]) && alltrue([for edge in values(docker_container.edge) : alltrue([for port in edge.ports : port.ip == "127.0.0.1" && port.external == 28450])])
    error_message = "Bank apps require bounded read-only containers, private ports, external read-only credentials and no Docker healthcheck platform."
  }
  assert {
    condition     = alltrue([for name in ["accounts", "front"] : !contains(docker_container.application[name].env, "BANK_ANALYTICS_ENABLED=true")])
    error_message = "Core-only startup must not apply analytics environment overrides."
  }
  assert {
    condition     = alltrue([for name, image in docker_image.application : image.triggers.security == filesha256("${var.project_directory}/config/jvm-network.security")])
    error_message = "Java image replacement must track the public JVM network-security build input."
  }
}

run "analytics_opt_in_adds_native_processes_without_new_storage_identity" {
  command = plan
  module { source = "./modules/bank" }
  variables { analytics_enabled = true }
  assert {
    condition = length(docker_container.application) == 7 && length(docker_container.platform) == 4 && length(docker_container.authorization) == 1 && length(docker_container.initializer) == 3 && length(docker_volume.data) == 6 && (
      docker_volume.data["analytics-hot"].name == "java-tofu-sprint09-analytics-hot" &&
      docker_volume.data["analytics-cold"].name == "java-tofu-sprint09-analytics-cold" &&
      docker_container.platform["cassandra"].memory == 1024
    )
    error_message = "Optional analytics adds one Java service and private Cassandra/ClickHouse processes while retaining the same volume identities."
  }
  assert {
    condition = alltrue([for name in ["accounts", "front"] : (
      contains(docker_container.application[name].env, "BANK_ANALYTICS_ENABLED=true") &&
      contains(docker_container.application[name].env, "SPRING_APPLICATION_NAME=${name}") &&
      contains(docker_container.application[name].env, "BANK_SECURITY_ISSUER=https://auth-bank.valgrind-home.localhost:28450/realms/bank") &&
      docker_container.application[name].read_only && docker_container.application[name].memory == 384 &&
      length(docker_container.application[name].volumes) == 1
    )])
    error_message = "Partial analytics environment overrides must retain core identity, limits and secret mounts."
  }
}
