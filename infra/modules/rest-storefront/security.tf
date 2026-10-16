resource "docker_volume" "sessions" {
  count = var.oauth_enabled ? 1 : 0
  name  = "${var.stack_name}-sessions-data"
  lifecycle {
    prevent_destroy = true
  }
}

resource "docker_container" "sessions" {
  count      = var.oauth_enabled ? 1 : 0
  name       = "${var.stack_name}-sessions"
  image      = docker_image.redis.image_id
  entrypoint = ["sh", "/setup/start-sessions.sh"]
  # The entrypoint initializes ownership, then immediately drops to the host secret-file UID.
  env = ["SESSION_RUNTIME_USER=${var.runtime_user}",
  "COURSE_CONFIG_SHA256=${sha256(join("", [for name in ["redis.conf", "start-sessions.sh"] : filesha256("${var.project_directory}/sessions/${name}")]))}"]
  memory       = 128
  memory_swap  = 128
  cpus         = "1.0"
  read_only    = true
  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 60
  tmpfs        = { "/tmp" = "rw,noexec,nosuid,size=1m,mode=1777" }
  networks_advanced {
    name    = docker_network.application.name
    aliases = ["sessions"]
  }
  volumes {
    volume_name    = docker_volume.sessions[0].name
    container_path = "/data"
  }
  dynamic "volumes" {
    for_each = {
      "/etc/redis/sessions.conf"             = "${var.project_directory}/sessions/redis.conf"
      "/setup/start-sessions.sh"             = "${var.project_directory}/sessions/start-sessions.sh"
      "/run/secrets/session_redis_users.acl" = "${var.secrets_directory}/session_redis_users.acl"
      "/run/secrets/session_redis_password"  = "${var.secrets_directory}/session_redis_password"
    }
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  healthcheck {
    test     = ["CMD-SHELL", "REDISCLI_AUTH=\"$(cat /run/secrets/session_redis_password)\" redis-cli --user sessions --no-auth-warning ping | grep -qx PONG"]
    interval = "3s"
    timeout  = "2s"
    retries  = 20
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
}

resource "docker_image" "security" {
  for_each = var.oauth_enabled ? {
    gateway = "caddy:2.11.7-alpine@sha256:d8542f48d34a9cf4e4c11a478865229840e87e4c96ea3f439101f31a5d35f75f"
    mailpit = "axllent/mailpit:v1.31.6@sha256:23fe39e81bde596a918000b3135c0f85dc8bb4c9ea6be274b051143f3e300387"
  } : {}
  name         = each.value
  keep_locally = true
}

resource "docker_container" "mailpit" {
  count = var.oauth_enabled ? 1 : 0
  name  = "${var.stack_name}-mailpit"
  image = docker_image.security["mailpit"].image_id
  user  = var.runtime_user
  env = ["MP_DATABASE=/tmp/mailpit.db", "MP_MAX_MESSAGES=500", "MP_MAX_MESSAGE_SIZE=1",
  "MP_ALLOWED_HOSTS=localhost,127.0.0.1,[::1]", "MP_DISABLE_VERSION_CHECK=true"]
  memory      = 96
  memory_swap = 96
  cpus        = "0.5"
  read_only   = true
  restart     = "unless-stopped"
  tmpfs       = { "/tmp" = "rw,noexec,nosuid,size=32m,mode=1777" }
  networks_advanced {
    name    = docker_network.application.name
    aliases = ["mailpit"]
  }
  ports {
    internal = 8025
    external = var.mail_port
    ip       = "127.0.0.1"
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
}

resource "docker_container" "gateway" {
  count       = var.oauth_enabled ? 1 : 0
  name        = "${var.stack_name}-gateway"
  image       = docker_image.security["gateway"].image_id
  user        = var.runtime_user
  command     = ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
  env         = ["TLS_PORT=${var.tls_port}", "COURSE_CONFIG_SHA256=${filesha256("${var.project_directory}/gateway/Caddyfile")}"]
  memory      = 96
  memory_swap = 96
  cpus        = "0.5"
  read_only   = true
  restart     = "unless-stopped"
  tmpfs       = { for directory in ["/tmp", "/config", "/data"] : directory => "rw,noexec,nosuid,size=1m,mode=1777" }
  networks_advanced {
    name    = docker_network.application.name
    aliases = ["gateway"]
  }
  ports {
    internal = 8443
    external = var.tls_port
    ip       = "127.0.0.1"
  }
  dynamic "volumes" {
    for_each = {
      "/etc/caddy/Caddyfile"             = "${var.project_directory}/gateway/Caddyfile"
      "/run/secrets/tls_certificate.pem" = "${var.secrets_directory}/tls_certificate.pem"
      "/run/secrets/tls_private_key.pem" = "${var.secrets_directory}/tls_private_key.pem"
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
  depends_on = [docker_container.application, docker_container.authorization]
}
