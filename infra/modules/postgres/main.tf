terraform {
  required_providers {
    docker = { source = "registry.terraform.io/kreuzwerker/docker" }
  }
}

variable "stack_name" {
  description = "Unique stack prefix for resource names."
  type        = string
}
variable "network_name" {
  description = "Private application network."
  type        = string
}
variable "database_name" {
  description = "Database and application login created on first initialization."
  type        = string
}
variable "database_user" {
  description = "Optional database owner login; defaults to the database name for existing stacks."
  type        = string
  default     = null
}
variable "password_file" {
  description = "External password file mounted read-only, never read into state."
  type        = string
}
variable "init_sql" {
  description = "Optional existing initialization SQL file for an empty data volume."
  type        = string
  default     = null
}
variable "network_aliases" {
  description = "DNS aliases; independent databases on one network need distinct names."
  type        = set(string)
  default     = ["db", "database"]
}
variable "memory_mb" {
  description = "Optional local container memory and total swap budget in MiB."
  type        = number
  default     = null
}
variable "cpus" {
  description = "Optional local CPU quota."
  type        = string
  default     = null
}
variable "entrypoint" {
  description = "Optional initialization wrapper; existing stacks use the image entrypoint."
  type        = list(string)
  default     = null
}
variable "command" {
  description = "Optional command for a custom initialization entrypoint."
  type        = list(string)
  default     = null
}
variable "readonly_files" {
  description = "Additional read-only host files keyed by their absolute container path."
  type        = map(string)
  default     = {}
}
variable "tmpfs" {
  description = "Optional temporary runtime filesystems; secret copies never need persistent storage."
  type        = map(string)
  default     = null
}
variable "tcp_healthcheck" {
  description = "Wait for the final TCP listener instead of the temporary initdb Unix socket."
  type        = bool
  default     = false
}

resource "docker_image" "postgres" {
  name         = "postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24"
  keep_locally = true
}

resource "docker_volume" "data" {
  name = "${var.stack_name}-postgres-data"
  lifecycle {
    # Redeploying a container must not silently reset an accepted project database.
    prevent_destroy = true
  }
}

resource "docker_container" "database" {
  name        = "${var.stack_name}-db"
  image       = docker_image.postgres.image_id
  restart     = "unless-stopped"
  wait        = true
  memory      = var.memory_mb
  memory_swap = var.memory_mb
  cpus        = var.cpus
  entrypoint  = var.entrypoint
  command     = var.command
  tmpfs       = var.tmpfs
  env = [
    "POSTGRES_DB=${var.database_name}",
    "POSTGRES_USER=${coalesce(var.database_user, var.database_name)}",
    "POSTGRES_PASSWORD_FILE=/run/secrets/db_password",
  ]
  networks_advanced {
    name    = var.network_name
    aliases = var.network_aliases
  }
  volumes {
    volume_name    = docker_volume.data.name
    container_path = "/var/lib/postgresql/data"
  }
  volumes {
    host_path      = var.password_file
    container_path = "/run/secrets/db_password"
    read_only      = true
  }
  dynamic "volumes" {
    for_each = var.init_sql == null ? [] : [var.init_sql]
    content {
      host_path      = volumes.value
      container_path = "/docker-entrypoint-initdb.d/01-schema.sql"
      read_only      = true
    }
  }
  dynamic "volumes" {
    for_each = var.readonly_files
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  healthcheck {
    test     = ["CMD-SHELL", var.tcp_healthcheck ? "pg_isready -h 127.0.0.1 -U $POSTGRES_USER -d $POSTGRES_DB" : "pg_isready -U $POSTGRES_USER -d $POSTGRES_DB"]
    interval = "2s"
    timeout  = "3s"
    retries  = 30
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
}

output "container_name" {
  description = "Ready PostgreSQL container; referencing it preserves startup ordering."
  value       = docker_container.database.name
}
output "image_id" {
  description = "Pinned PostgreSQL image for one-shot setup jobs."
  value       = docker_image.postgres.image_id
}
