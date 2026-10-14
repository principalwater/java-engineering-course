mock_provider "docker" {}

variables {
  stack_name    = "java-tofu-sprint07-postgresql-storefront"
  network_name  = "java-tofu-sprint07-postgresql"
  database_name = "storefront"
  password_file = "/tmp/course-test-secrets/db_password"
}

run "accepted_database_defaults" {
  command = plan
  module {
    source = "./modules/postgres"
  }
  assert {
    condition = alltrue([for network in docker_container.database.networks_advanced : (
      network.aliases == toset(["db", "database"])
      )]) && contains(docker_container.database.env, "POSTGRES_USER=storefront") && (
      docker_container.database.healthcheck[0].test == tolist(["CMD-SHELL", "pg_isready -U $POSTGRES_USER -d $POSTGRES_DB"])
    )
    error_message = "Existing deployments must retain their accepted database DNS aliases."
  }
}

run "separate_payments_owner_and_runtime_bootstrap" {
  command = plan
  module {
    source = "./modules/postgres"
  }
  variables {
    database_name   = "payments"
    database_user   = "payments_owner"
    password_file   = "/tmp/course-test-secrets/payments_db_password"
    init_sql        = "/tmp/course-project/payments/schema.sql"
    entrypoint      = ["sh", "/setup/start-payments-db.sh"]
    command         = ["postgres"]
    tcp_healthcheck = true
    tmpfs           = { "/run/payments-secrets" = "rw,noexec,nosuid,size=1m,mode=0700" }
    readonly_files = {
      "/setup/start-payments-db.sh"                    = "/tmp/course-project/scripts/start-payments-db.sh"
      "/docker-entrypoint-initdb.d/02-runtime-role.sh" = "/tmp/course-project/scripts/init-payments-db.sh"
      "/setup/postgresql-runtime-grants.sql"           = "/tmp/course-project/payments/postgresql-runtime-grants.sql"
      "/run/secrets/payments_runtime_password"         = "/tmp/course-test-secrets/payments_runtime_password"
    }
  }
  assert {
    condition = contains(docker_container.database.env, "POSTGRES_USER=payments_owner") && (
      docker_container.database.entrypoint == tolist(["sh", "/setup/start-payments-db.sh"]) &&
      docker_container.database.command == tolist(["postgres"]) &&
      docker_container.database.healthcheck[0].test == tolist(["CMD-SHELL", "pg_isready -h 127.0.0.1 -U $POSTGRES_USER -d $POSTGRES_DB"]) &&
      docker_container.database.tmpfs["/run/payments-secrets"] == "rw,noexec,nosuid,size=1m,mode=0700" &&
      alltrue([for mount in docker_container.database.volumes : mount.read_only || mount.container_path == "/var/lib/postgresql/data"]) &&
      length([for mount in docker_container.database.volumes : mount if mount.container_path == "/docker-entrypoint-initdb.d/01-schema.sql"]) == 1 &&
      length([for mount in docker_container.database.volumes : mount if mount.container_path == "/run/secrets/payments_runtime_password"]) == 1
    )
    error_message = "Payments must bootstrap an isolated owner/runtime schema through private read-only files before final TCP readiness."
  }
}

run "independent_database_alias_and_budget" {
  command = plan
  module {
    source = "./modules/postgres"
  }
  variables {
    network_aliases = ["store-db"]
    memory_mb       = 256
    cpus            = "1.0"
  }
  assert {
    condition = docker_container.database.memory == 256 && docker_container.database.memory_swap == 256 && (
      docker_container.database.cpus == "1.0" &&
      alltrue([for network in docker_container.database.networks_advanced : network.aliases == toset(["store-db"])])
    )
    error_message = "The selected database must receive its own DNS alias and actual container resource budget."
  }
}
