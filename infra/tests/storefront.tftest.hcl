mock_provider "docker" {}

variables {
  project_directory = abspath("../projects/sprint05-storefront")
  stack_name        = "java-tofu-sprint05-postgresql"
  profile           = "postgresql"
  http_port         = 28085
  analytics_port    = 28125
  analytics_enabled = false
  secrets_directory = "/tmp/course-test-secrets"
  runtime_user      = "10001:10001"
}

run "existing_jdbc_contract" {
  command = plan
  module {
    source = "./modules/storefront"
  }
  assert {
    condition = contains(docker_container.application.env, "DB_URL=jdbc:postgresql://db:5432/storefront") && contains(
      docker_container.application.command, "--server.servlet.session.cookie.name=java-tofu-sprint05-postgresql-session"
      ) && length([for mount in docker_container.application.volumes : mount if(
        mount.container_path == "/run/secrets/spring.datasource.password" && mount.read_only
    )]) == 1
    error_message = "The existing JDBC URL, password mount and session namespace must remain compatible."
  }
}

run "native_r2dbc_contract" {
  command = plan
  module {
    source = "./modules/storefront"
  }
  variables {
    stack_name                 = "java-tofu-sprint06-postgresql"
    analytics_enabled          = true
    http_port                  = 28087
    analytics_port             = 28127
    database_protocol          = "r2dbc"
    database_password_property = "spring.r2dbc.password"
    session_cookie_property    = "server.reactive.session.cookie.name"
  }
  assert {
    condition = contains(docker_container.application.env, "DB_URL=r2dbc:postgresql://db:5432/storefront") && contains(
      docker_container.application.command, "--server.reactive.session.cookie.name=java-tofu-sprint06-postgresql-session"
      ) && length([for mount in docker_container.application.volumes : mount if(
        mount.container_path == "/run/secrets/spring.r2dbc.password" && mount.read_only
      )]) == 1 && output.deployment.backend_url == "http://127.0.0.1:28087" && (
      output.deployment.analytics_url == "http://127.0.0.1:28127" &&
      docker_volume.analytics_data.name == "java-tofu-sprint06-postgresql-clickhouse-data"
    )
    error_message = "The reactive deployment requires its native R2DBC URL, password mount and isolated cookies."
  }
}

run "native_h2_isolation" {
  command = plan
  module {
    source = "./modules/storefront"
  }
  variables {
    stack_name                 = "java-tofu-sprint06-h2"
    profile                    = "h2"
    http_port                  = 28088
    analytics_port             = 28128
    database_protocol          = "r2dbc"
    database_password_property = "spring.r2dbc.password"
    session_cookie_property    = "server.reactive.session.cookie.name"
  }
  assert {
    condition = length(module.postgres) == 0 && !contains(keys(output.deployment.containers), "database") && (
      length([for setting in docker_container.application.env : setting if startswith(setting, "DB_URL=")]) == 0
      ) && length([for mount in docker_container.application.volumes : mount if(
        mount.container_path == "/run/secrets/spring.r2dbc.password"
    )]) == 0 && output.deployment.backend_url == "http://127.0.0.1:28088"
    error_message = "Native H2 must not share a PostgreSQL server or credentials and needs its own loopback endpoint."
  }
}

run "mismatched_password_contract" {
  command = plan
  module {
    source = "./modules/storefront"
  }
  variables {
    database_protocol = "r2dbc"
  }
  expect_failures = [var.database_password_property]
}
