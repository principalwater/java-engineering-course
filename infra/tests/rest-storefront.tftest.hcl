mock_provider "docker" {}

variables {
  # Accepted sources supply real Docker/analytics files; the provider remains mocked.
  project_directory = abspath("../projects/sprint06-reactive-storefront")
  stack_name        = "java-tofu-sprint07-postgresql"
  profile           = "postgresql"
  artifact_hashes = {
    storefront = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    payments   = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
  http_port         = 28089
  payments_port     = 29089
  analytics_port    = 28129
  analytics_enabled = false
  secrets_directory = "/tmp/course-test-secrets"
  runtime_user      = "10001:10001"
}

run "two_services_and_independent_databases" {
  command = plan
  module {
    source = "./modules/rest-storefront"
  }
  assert {
    condition = length(docker_container.application) == 2 && length(module.postgres) == 2 && (
      contains(docker_container.application["storefront"].env, "DB_URL=r2dbc:postgresql://store-db:5432/storefront") &&
      contains(docker_container.application["payments"].env, "PAYMENTS_DB_URL=r2dbc:postgresql://payments-db:5432/payments") &&
      contains(docker_container.application["payments"].env, "PAYMENTS_DB_USER=payments_runtime") &&
      contains(docker_container.application["payments"].env, "PAYMENTS_SQL_INIT_MODE=never") &&
      contains(docker_container.application["storefront"].env, "STORE_PAYMENTS_URL=http://payments:8080") &&
      contains(docker_container.application["storefront"].env, "SPRING_DATA_REDIS_USERNAME=storefront") &&
      module.postgres["storefront"].container_name != module.postgres["payments"].container_name
    )
    error_message = "Storefront and payments must use distinct databases and the private native service endpoints."
  }
  assert {
    condition = docker_container.application["storefront"].memory == 512 && (
      docker_container.application["payments"].memory == 384
      ) && alltrue([for service in values(docker_container.application) : (
        service.memory_swap == service.memory && service.cpus == "1.0" &&
        alltrue([for port in service.ports : port.ip == "127.0.0.1"]) &&
        alltrue([for mount in service.volumes : mount.read_only])
        )]) && length([for mount in docker_container.application["payments"].volumes : mount if(
        mount.container_path == "/run/secrets/spring.r2dbc.password" && endswith(mount.host_path, "/payments_runtime_password")
    )]) == 1 && output.deployment.payments_url == "http://127.0.0.1:29089"
    error_message = "Both Java services need real resource limits, loopback ports and separate read-only credentials."
  }
  assert {
    condition = docker_container.redis.memory == 128 && docker_container.redis.memory_swap == 128 && (
      length(docker_container.redis.ports) == 0 && length(docker_container.redis.command) == 1 &&
      length([for mount in docker_container.redis.volumes : mount if(
        mount.container_path == docker_container.redis.command[0] && mount.read_only
      )]) == 1 &&
      length([for mount in docker_container.redis.volumes : mount if(
        mount.container_path == "/run/secrets/redis_users.acl" && mount.read_only
      )]) == 1
    )
    error_message = "Redis is an authenticated bounded private cache; it must not expose a host port or consume unbounded memory."
  }
}

run "native_h2_without_external_databases" {
  command = plan
  module {
    source = "./modules/rest-storefront"
  }
  variables {
    stack_name        = "java-tofu-sprint07-h2"
    profile           = "h2"
    http_port         = 28090
    payments_port     = 29090
    analytics_port    = 28130
    analytics_enabled = true
  }
  assert {
    condition = length(module.postgres) == 0 && alltrue([
      for service in values(docker_container.application) : (
        length([for setting in service.env : setting if can(regex("^(PAYMENTS_)?DB_URL=", setting))]) == 0 &&
        length([for mount in service.volumes : mount if mount.container_path == "/run/secrets/spring.r2dbc.password"]) == 0
      )
      ]) && docker_volume.analytics_data.name == "java-tofu-sprint07-h2-clickhouse-data" && (
      output.deployment.frontend_url == "http://127.0.0.1:28090" &&
      output.deployment.payments_url == "http://127.0.0.1:29090" &&
      output.deployment.analytics_url == "http://127.0.0.1:28130"
    )
    error_message = "H2 services must use their own JVM memory without external database credentials and retain isolated analytics storage."
  }
}

run "missing_payment_artifact" {
  command = plan
  module {
    source = "./modules/rest-storefront"
  }
  variables {
    artifact_hashes = { storefront = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  }
  expect_failures = [var.artifact_hashes]
}
