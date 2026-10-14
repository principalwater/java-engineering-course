terraform {
  required_providers {
    docker = { source = "registry.terraform.io/kreuzwerker/docker" }
  }
}

locals {
  persistent = var.profile == "postgresql"
  services = {
    storefront = {
      port = var.http_port
      # SQL fallback under a Redis outage exhausted the former 384 MiB cgroup budget.
      memory_mb = 512
      secrets = merge({
        "store.payments.service-token"                  = "payments_service_token"
        "spring.data.redis.password"                    = "redis_password"
        "store.seller.bootstrap-password"               = "seller_password"
        }, local.persistent ? { "spring.r2dbc.password" = "db_password" } : {}, var.analytics_enabled ? {
        "store.analytics.writer-password"               = "analytics_writer_password"
        "store.analytics.reader-password"               = "analytics_reader_password"
      } : {})
      environment = concat([
        "STORE_PAYMENTS_URL=http://payments:8080",
        "SPRING_DATA_REDIS_HOST=redis", "SPRING_DATA_REDIS_PORT=6379", "SPRING_DATA_REDIS_USERNAME=storefront",
        "STORE_CACHE_NAMESPACE=${var.stack_name}",
        "STORE_SECRETS_LOCATION=/run/secrets/",
        ], local.persistent ? [
        "DB_URL=r2dbc:postgresql://store-db:5432/storefront", "DB_USER=storefront",
        ] : [], var.analytics_enabled ? [
        "STORE_ANALYTICS_ENABLED=true", "STORE_ANALYTICS_URL=http://clickhouse:8123",
      ] : [])
      command = [
        "--store.cart.cookie-name=${var.stack_name}-cart",
        "--server.reactive.session.cookie.name=${var.stack_name}-session",
      ]
    }
    payments = {
      port      = var.payments_port
      memory_mb = 384
      secrets = merge({
        "payments.service-token" = "payments_service_token"
      }, local.persistent ? { "spring.r2dbc.password" = "payments_runtime_password" } : {})
      environment = local.persistent ? [
        "PAYMENTS_DB_URL=r2dbc:postgresql://payments-db:5432/payments", "PAYMENTS_DB_USER=payments_runtime",
        "PAYMENTS_SQL_INIT_MODE=never",
      ] : []
      command = []
    }
  }
}

resource "docker_network" "application" {
  name = var.stack_name
}

module "postgres" {
  source   = "../postgres"
  for_each = local.persistent ? toset(keys(local.services)) : toset([])

  stack_name      = "${var.stack_name}-${each.key}"
  network_name    = docker_network.application.name
  network_aliases = [each.key == "storefront" ? "store-db" : "payments-db"]
  database_name   = each.key
  database_user   = each.key == "payments" ? "payments_owner" : "storefront"
  password_file   = "${var.secrets_directory}/${each.key == "storefront" ? "db_password" : "payments_db_password"}"
  init_sql        = each.key == "payments" ? "${var.project_directory}/payments/src/main/resources/db/schema.sql" : null
  entrypoint      = each.key == "payments" ? ["sh", "/setup/start-payments-db.sh"] : null
  command         = each.key == "payments" ? ["postgres"] : null
  readonly_files = each.key == "payments" ? {
    "/setup/start-payments-db.sh"                    = "${var.project_directory}/scripts/start-payments-db.sh"
    "/docker-entrypoint-initdb.d/02-runtime-role.sh" = "${var.project_directory}/scripts/init-payments-db.sh"
    "/setup/postgresql-runtime-grants.sql"           = "${var.project_directory}/payments/src/main/resources/db/postgresql-runtime-grants.sql"
    "/run/secrets/payments_runtime_password"         = "${var.secrets_directory}/payments_runtime_password"
  } : {}
  tmpfs           = each.key == "payments" ? { "/run/payments-secrets" = "rw,noexec,nosuid,size=1m,mode=0700" } : null
  tcp_healthcheck = true
  memory_mb       = 256
  cpus            = "1.0"
}

resource "docker_image" "application" {
  for_each     = local.services
  name         = "${var.stack_name}-${each.key}:local"
  keep_locally = true
  build {
    context = var.project_directory
    builder = "default"
    build_args = {
      JAR_FILE = "build/deployment/${each.key}.jar"
    }
  }
  triggers = {
    artifact   = var.artifact_hashes[each.key]
    dockerfile = filesha256("${var.project_directory}/Dockerfile")
    context    = filesha256("${var.project_directory}/.dockerignore")
  }
}

resource "docker_container" "application" {
  for_each = local.services

  name         = "${var.stack_name}-${each.key}"
  image        = docker_image.application[each.key].image_id
  command      = each.value.command
  user         = var.runtime_user
  memory       = each.value.memory_mb
  memory_swap  = each.value.memory_mb
  cpus         = "1.0"
  stop_timeout = 30
  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 180
  env = concat([
    "SERVER_PORT=8080", "SPRING_PROFILES_ACTIVE=${var.profile}",
    "SPRING_CONFIG_IMPORT=configtree:/run/secrets/",
  ], each.value.environment)
  networks_advanced {
    name    = docker_network.application.name
    aliases = [each.key]
  }
  ports {
    internal = 8080
    external = each.value.port
    ip       = "127.0.0.1"
  }
  dynamic "volumes" {
    for_each = each.value.secrets
    content {
      host_path      = "${var.secrets_directory}/${volumes.value}"
      container_path = "/run/secrets/${volumes.key}"
      read_only      = true
    }
  }
  healthcheck {
    test         = ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health/readiness"]
    interval     = "5s"
    timeout      = "3s"
    retries      = 30
    start_period = "30s"
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  # The for_each control-plane barrier waits for both databases during initial apply.
  # Running JVMs recover independently; replacing one database must not replace them.
  depends_on = [module.postgres]
}
