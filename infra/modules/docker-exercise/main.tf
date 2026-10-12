terraform {
  required_providers {
    docker = { source = "registry.terraform.io/kreuzwerker/docker" }
  }
}

variable "project_directory" {
  description = "Existing Docker exercise, including the locally packaged JAR."
  type        = string
}
variable "stack_name" {
  description = "Unique stack prefix."
  type        = string
}
variable "http_port" {
  description = "Loopback gateway port."
  type        = number
}
variable "secrets_directory" {
  description = "External directory containing db_password."
  type        = string
}
variable "runtime_user" {
  description = "Host UID:GID for the Java process reading the mounted password."
  type        = string
}

resource "docker_network" "application" {
  name = var.stack_name
}

module "postgres" {
  source = "../postgres"

  stack_name    = var.stack_name
  network_name  = docker_network.application.name
  database_name = "study"
  password_file = "${var.secrets_directory}/db_password"
  init_sql      = "${var.project_directory}/movies.sql"
}

resource "docker_image" "application" {
  name         = "${var.stack_name}-app:local"
  keep_locally = true
  build {
    context = var.project_directory
    builder = "default"
  }
  triggers = {
    artifact = try(filesha256("${var.project_directory}/target/docker-study.jar"), "not-packaged")
    recipe   = filesha256("${var.project_directory}/Dockerfile")
  }
  lifecycle {
    precondition {
      condition     = fileexists("${var.project_directory}/target/docker-study.jar")
      error_message = "Package docker-study.jar with the repository Maven wrapper first."
    }
  }
}

resource "docker_container" "application" {
  name        = "${var.stack_name}-app"
  image       = docker_image.application.image_id
  user        = var.runtime_user
  memory      = 512
  memory_swap = 512
  restart     = "unless-stopped"
  entrypoint  = ["sh", "-ec", "export POSTGRES_PASSWORD=\"$(cat /run/secrets/db_password)\"; test -n \"$POSTGRES_PASSWORD\"; exec java -jar /app/app.jar"]
  env         = ["DB_HOST=database", "POSTGRES_USER=study", "POSTGRES_DB=study"]
  networks_advanced {
    name    = docker_network.application.name
    aliases = ["app"]
  }
  volumes {
    host_path      = "${var.secrets_directory}/db_password"
    container_path = "/run/secrets/db_password"
    read_only      = true
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  depends_on = [module.postgres]
}

resource "docker_image" "gateway" {
  name         = "nginx:1.29-alpine@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de"
  keep_locally = true
}

resource "docker_container" "gateway" {
  name         = "${var.stack_name}-gateway"
  image        = docker_image.gateway.image_id
  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 120
  networks_advanced {
    name = docker_network.application.name
  }
  ports {
    internal = 80
    external = var.http_port
    ip       = "127.0.0.1"
  }
  volumes {
    host_path      = "${var.project_directory}/nginx.conf"
    container_path = "/etc/nginx/conf.d/default.conf"
    read_only      = true
  }
  healthcheck {
    # Check the complete routing/JDBC path rather than a running Nginx process.
    test     = ["CMD", "wget", "-q", "--spider", "http://127.0.0.1/movies"]
    interval = "2s"
    timeout  = "3s"
    retries  = 60
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  depends_on = [docker_container.application]
}

output "deployment" {
  description = "Gateway endpoint and managed exercise containers."
  value = {
    name          = var.stack_name
    frontend_url  = "http://127.0.0.1:${var.http_port}"
    backend_url   = null
    analytics_url = null
    containers = {
      database = module.postgres.container_name
      backend  = docker_container.application.name
      frontend = docker_container.gateway.name
    }
  }
}
