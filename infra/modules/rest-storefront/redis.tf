resource "docker_image" "redis" {
  name         = "redis@sha256:fbdbaea47b9ae4ecc2082ecdb4e1cea81e32176ffb1dcf643d422ad07427e5d9"
  keep_locally = true
}

resource "docker_container" "redis" {
  name       = "${var.stack_name}-redis"
  image      = docker_image.redis.image_id
  entrypoint = ["redis-server"]
  # Native config preserves save "" without an empty argv rejected by the Docker provider.
  command      = [local.redis_configuration_path]
  user         = var.runtime_user
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
    aliases = ["redis"]
  }
  volumes {
    host_path      = local.redis_configuration
    container_path = local.redis_configuration_path
    read_only      = true
  }
  volumes {
    host_path      = "${var.secrets_directory}/redis_users.acl"
    container_path = "/run/secrets/redis_users.acl"
    read_only      = true
  }
  volumes {
    host_path      = "${var.secrets_directory}/redis_password"
    container_path = "/run/secrets/redis_password"
    read_only      = true
  }
  healthcheck {
    test     = ["CMD-SHELL", "REDISCLI_AUTH=\"$(cat /run/secrets/redis_password)\" redis-cli --user storefront --no-auth-warning ping | grep -qx PONG"]
    interval = "5s"
    timeout  = "3s"
    retries  = 12
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
}
locals {
  redis_configuration      = abspath("${path.module}/redis.conf")
  redis_configuration_path = "/etc/redis/cache-${filesha256(local.redis_configuration)}.conf"
}
