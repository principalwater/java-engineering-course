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

run "default_keeps_the_shared_service_token" {
  command = plan
  module {
    source = "./modules/rest-storefront"
  }
  assert {
    condition = length(docker_container.authorization) == 0 && length(module.authorization_postgres) == 0 && (
      !can(output.deployment.issuer) &&
      length([for mount in docker_container.application["storefront"].volumes : mount if mount.container_path == "/run/secrets/store.payments.service-token"]) == 1 &&
      length([for mount in docker_container.application["payments"].volumes : mount if mount.container_path == "/run/secrets/payments.service-token"]) == 1 &&
      alltrue([for service in values(docker_container.application) : (
        length([for setting in service.env : setting if startswith(setting, "PAYMENTS_OAUTH_")]) == 0 &&
        contains(service.env, "SPRING_CONFIG_IMPORT=configtree:/run/secrets/")
      )])
    )
    error_message = "Without OAuth the Sprint 7 shared service-token topology must remain unchanged."
  }
}

run "oauth_authorization_server_and_secret_files" {
  command = plan
  module {
    source = "./modules/rest-storefront"
  }
  variables {
    # The fixture supplies only the files whose hashes the plan reads; Sprint 8 sources are absent from main.
    project_directory  = abspath("tests/fixtures/oauth-project")
    stack_name         = "java-tofu-sprint08-postgresql"
    http_port          = 28091
    payments_port      = 29091
    analytics_port     = 28131
    oauth_enabled      = true
    authorization_port = 28381
  }
  assert {
    condition = length(docker_container.authorization) == 1 && length(module.authorization_postgres) == 1 && (
      module.authorization_postgres[0].container_name == "java-tofu-sprint08-postgresql-authorization-db" &&
      output.deployment.containers.authorization == "java-tofu-sprint08-postgresql-authorization" &&
      output.deployment.containers.authorization_database == module.authorization_postgres[0].container_name &&
      output.deployment.issuer == "http://localhost:28381/realms/storefront-api"
    )
    error_message = "OAuth must add Keycloak with its own authorization database and the canonical public issuer."
  }
  assert {
    condition = docker_container.authorization[0].read_only && docker_container.authorization[0].user == "10001:10001" && (
      docker_container.authorization[0].memory == 768 && docker_container.authorization[0].memory_swap == 768 &&
      docker_container.authorization[0].tmpfs["/tmp"] == "rw,noexec,nosuid,size=64m,mode=1777" &&
      docker_container.authorization[0].tmpfs["/opt/keycloak/data"] == "rw,noexec,nosuid,size=32m,mode=1777" &&
      alltrue([for port in docker_container.authorization[0].ports : port.ip == "127.0.0.1" && port.external == 28381]) &&
      alltrue([for mount in docker_container.authorization[0].volumes : mount.read_only]) &&
      length([for mount in docker_container.authorization[0].volumes : mount if mount.container_path == "/opt/keycloak/data/import/storefront-api-realm.json"]) == 1
    )
    error_message = "Keycloak needs a read-only root, bounded tmpfs, read-only realm and secret files, and a loopback port."
  }
  assert {
    condition = alltrue([
      for container in concat([docker_container.authorization[0]], values(docker_container.application)) :
      alltrue([for setting in container.env : !can(regex("(PASSWORD|SECRET|TOKEN)=", setting))])
      ]) && length([for mount in docker_container.authorization[0].volumes : mount if(
        startswith(mount.container_path, "/run/secrets/") && startswith(mount.host_path, "/tmp/course-test-secrets/")
    )]) == 3
    error_message = "Secrets must be mounted files; no container environment may carry a password, token or client secret."
  }
  assert {
    condition = length([for mount in docker_container.application["storefront"].volumes : mount if(
      mount.container_path == "/run/secrets/spring.security.oauth2.client.registration.payments.client-secret" &&
      endswith(mount.host_path, "/oauth_client_secret")
      )]) == 1 && length([for mount in docker_container.application["storefront"].volumes : mount if endswith(mount.container_path, "service-token")]) == 0 && (
      length([for mount in docker_container.application["payments"].volumes : mount if endswith(mount.container_path, "service-token") || endswith(mount.host_path, "/oauth_client_secret")]) == 0 &&
      contains(docker_container.application["storefront"].env, "PAYMENTS_OAUTH_TOKEN_URI=http://authorization:8080/realms/storefront-api/protocol/openid-connect/token") &&
      contains(docker_container.application["payments"].env, "PAYMENTS_OAUTH_ISSUER=http://localhost:28381/realms/storefront-api") &&
      contains(docker_container.application["payments"].env, "PAYMENTS_OAUTH_JWK_SET_URI=http://authorization:8080/realms/storefront-api/protocol/openid-connect/certs")
    )
    error_message = "Only the storefront receives the client secret; payments validates issuer and JWKs without a preshared bearer."
  }
}

run "oauth_h2_payments_without_secret_files" {
  command = plan
  module {
    source = "./modules/rest-storefront"
  }
  variables {
    project_directory  = abspath("tests/fixtures/oauth-project")
    stack_name         = "java-tofu-sprint08-h2"
    profile            = "h2"
    http_port          = 28092
    payments_port      = 29092
    analytics_port     = 28132
    oauth_enabled      = true
    authorization_port = 28382
  }
  assert {
    condition = length(module.postgres) == 0 && length(module.authorization_postgres) == 1 && (
      length(docker_container.application["payments"].volumes) == 0 &&
      length([for setting in docker_container.application["payments"].env : setting if startswith(setting, "SPRING_CONFIG_IMPORT=")]) == 0 &&
      contains(docker_container.application["storefront"].env, "SPRING_CONFIG_IMPORT=configtree:/run/secrets/") &&
      output.deployment.token_url == "http://127.0.0.1:28382/realms/storefront-api/protocol/openid-connect/token"
    )
    error_message = "H2 payments has no secret files, so it must not require a configtree; the storefront and Keycloak still keep theirs."
  }
}

run "oauth_requires_the_public_issuer_port" {
  command = plan
  module {
    source = "./modules/rest-storefront"
  }
  variables {
    project_directory = abspath("tests/fixtures/oauth-project")
    oauth_enabled     = true
  }
  expect_failures = [var.authorization_port]
}
