terraform {
  required_providers {
    docker = { source = "registry.terraform.io/kreuzwerker/docker" }
  }
}

locals {
  persistent = var.profile == "postgresql"
  sources = setunion(
    fileset(var.project_directory, "src/main/**"),
    fileset(var.project_directory, "gradle/**"),
    toset(["Dockerfile", ".dockerignore", "gradlew", "build.gradle", "settings.gradle"]),
  )
  secret_mounts = merge(
    local.persistent ? { "spring.datasource.password" = "db_password" } : {},
    var.analytics_enabled ? {
      "store.analytics.writer-password" = "analytics_writer_password"
      "store.analytics.reader-password" = "analytics_reader_password"
    } : {},
  )
}

resource "docker_network" "application" {
  name = var.stack_name
}

module "postgres" {
  source = "../postgres"
  count  = local.persistent ? 1 : 0

  stack_name    = var.stack_name
  network_name  = docker_network.application.name
  database_name = "storefront"
  password_file = "${var.secrets_directory}/db_password"
}

resource "docker_image" "application" {
  name         = "${var.stack_name}-backend:local"
  keep_locally = true
  build {
    context = var.project_directory
    builder = "default"
  }
  triggers = {
    source = sha256(join("", [for name in sort(tolist(local.sources)) : "${name}:${filesha256("${var.project_directory}/${name}")}"]))
  }
}

resource "docker_container" "application" {
  name  = "${var.stack_name}-backend"
  image = docker_image.application.image_id
  # Cookies are not port-scoped; parallel Compose and OpenTofu stores need distinct identities.
  command = [
    "--store.cart.cookie-name=${var.stack_name}-cart",
    "--server.servlet.session.cookie.name=${var.stack_name}-session",
  ]
  user         = var.runtime_user
  memory       = 512
  memory_swap  = 512
  stop_timeout = 30
  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 180
  env = concat([
    "SERVER_PORT=8080", "SPRING_PROFILES_ACTIVE=${var.profile}",
    ], local.persistent ? [
    "DB_URL=jdbc:postgresql://db:5432/storefront", "DB_USER=storefront",
    ] : [], var.analytics_enabled ? [
    "STORE_ANALYTICS_ENABLED=true", "STORE_ANALYTICS_URL=http://clickhouse:8123",
    "SPRING_CONFIG_IMPORT=configtree:/run/secrets/",
  ] : [])
  networks_advanced {
    name = docker_network.application.name
  }
  ports {
    internal = 8080
    external = var.http_port
    ip       = "127.0.0.1"
  }
  dynamic "volumes" {
    for_each = local.secret_mounts
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
  # The database must be ready; optional analytics must not gate application startup.
  depends_on = [module.postgres]
}
