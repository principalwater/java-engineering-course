resource "docker_container" "analytics_reader" {
  count = var.analytics_enabled ? 1 : 0

  name     = "${var.stack_name}-analytics-reader"
  image    = module.postgres.image_id
  must_run = false
  attach   = true
  command  = ["sh", "/setup/create-reader.sh"]
  env = [
    "PGHOST=db", "PGDATABASE=blog", "PGUSER=blog",
    "PGPASSWORD_FILE=/run/secrets/db_password",
    "READER_PASSWORD_FILE=/run/secrets/analytics_db_password",
  ]
  networks_advanced {
    name = docker_network.application.name
  }
  dynamic "volumes" {
    for_each = {
      "/run/secrets/db_password"           = "${var.secrets_directory}/db_password"
      "/run/secrets/analytics_db_password" = "${var.secrets_directory}/analytics_db_password"
      "/setup/create-reader.sh"            = "${var.project_directory}/analytics/create-reader.sh"
    }
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  lifecycle {
    postcondition {
      condition     = self.exit_code == 0
      error_message = "Creating the analytics reader failed."
    }
  }
  depends_on = [docker_container.backend]
}

resource "docker_image" "clickhouse" {
  count        = var.analytics_enabled ? 1 : 0
  name         = "clickhouse/clickhouse-server:26.8-alpine@sha256:58f554637bfe3b77e0335b4ff90611279dbdf8d262dc15225ee93901853dfa13"
  keep_locally = true
}

resource "docker_volume" "analytics_data" {
  count = var.analytics_enabled ? 1 : 0
  name  = "${var.stack_name}-clickhouse-data"
  lifecycle {
    prevent_destroy = true
  }
}

resource "docker_container" "clickhouse" {
  count = var.analytics_enabled ? 1 : 0

  name         = "${var.stack_name}-clickhouse"
  image        = docker_image.clickhouse[0].image_id
  entrypoint   = ["sh", "/setup/start-clickhouse.sh"]
  restart      = "unless-stopped"
  memory       = 768
  memory_swap  = 768
  wait         = true
  wait_timeout = 120
  env = [
    "CLICKHOUSE_USER=analyst", "CLICKHOUSE_PASSWORD_FILE=/run/secrets/clickhouse_password",
    "CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=0", "PG_DATABASE=blog",
    "PG_READER_PASSWORD_FILE=/run/secrets/analytics_db_password",
  ]
  networks_advanced {
    name = docker_network.application.name
  }
  ports {
    internal = 8123
    external = var.analytics_port
    ip       = "127.0.0.1"
  }
  volumes {
    volume_name    = docker_volume.analytics_data[0].name
    container_path = "/var/lib/clickhouse"
  }
  dynamic "volumes" {
    for_each = {
      "/run/secrets/analytics_db_password"             = "${var.secrets_directory}/analytics_db_password"
      "/run/secrets/clickhouse_password"               = "${var.secrets_directory}/clickhouse_password"
      "/etc/clickhouse-server/config.d/postgresql.xml" = "${var.project_directory}/analytics/postgresql.xml"
      "/etc/clickhouse-server/users.d/readonly.xml"    = "${var.project_directory}/analytics/readonly.xml"
      "/setup/start-clickhouse.sh"                     = "${var.project_directory}/analytics/start-clickhouse.sh"
    }
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  healthcheck {
    test     = ["CMD", "wget", "-q", "--spider", "http://127.0.0.1:8123/ping"]
    interval = "5s"
    timeout  = "3s"
    retries  = 24
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  depends_on = [docker_container.analytics_reader]
}
