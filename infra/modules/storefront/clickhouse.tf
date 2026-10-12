resource "docker_volume" "analytics_data" {
  name = "${var.stack_name}-clickhouse-data"
  lifecycle {
    prevent_destroy = true
  }
}

resource "docker_image" "clickhouse" {
  count        = var.analytics_enabled ? 1 : 0
  name         = "clickhouse/clickhouse-server:26.8-alpine@sha256:58f554637bfe3b77e0335b4ff90611279dbdf8d262dc15225ee93901853dfa13"
  keep_locally = true
}

resource "docker_container" "clickhouse" {
  count = var.analytics_enabled ? 1 : 0

  name         = "${var.stack_name}-clickhouse"
  image        = docker_image.clickhouse[0].image_id
  entrypoint   = ["sh", "/setup/start-clickhouse.sh"]
  memory       = 768
  memory_swap  = 768
  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 120
  env = [
    "CLICKHOUSE_USER=storefront_admin", "CLICKHOUSE_DB=storefront",
    "CLICKHOUSE_PASSWORD_FILE=/run/secrets/analytics_admin_password",
    "CLICKHOUSE_ALWAYS_RUN_INITDB_SCRIPTS=1",
    "COURSE_CONFIG_SHA256=${sha256(join("", [for name in ["users.xml", "001-order-lines.sql", "start-clickhouse.sh"] : filesha256("${var.project_directory}/analytics/${name}")]))}",
  ]
  networks_advanced {
    name    = docker_network.application.name
    aliases = ["clickhouse"]
  }
  ports {
    internal = 8123
    external = var.analytics_port
    ip       = "127.0.0.1"
  }
  volumes {
    volume_name    = docker_volume.analytics_data.name
    container_path = "/var/lib/clickhouse"
  }
  dynamic "volumes" {
    for_each = {
      "/run/secrets/analytics_admin_password"           = "${var.secrets_directory}/analytics_admin_password"
      "/run/secrets/analytics_writer_password"          = "${var.secrets_directory}/analytics_writer_password"
      "/run/secrets/analytics_reader_password"          = "${var.secrets_directory}/analytics_reader_password"
      "/etc/clickhouse-server/users.d/storefront.xml"   = "${var.project_directory}/analytics/users.xml"
      "/docker-entrypoint-initdb.d/001-order-lines.sql" = "${var.project_directory}/analytics/001-order-lines.sql"
      "/setup/start-clickhouse.sh"                      = "${var.project_directory}/analytics/start-clickhouse.sh"
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
}
