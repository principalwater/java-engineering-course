terraform {
  required_providers {
    docker = { source = "registry.terraform.io/kreuzwerker/docker" }
  }
}

locals {
  origins = "http://localhost:${var.frontend_port},http://127.0.0.1:${var.frontend_port}"
  backend_files = setunion(
    fileset(var.project_directory, "src/main/**"),
    var.spring_boot ? fileset(var.project_directory, "gradle/**") : fileset(var.project_directory, ".mvn/**"),
    toset(var.spring_boot ? ["Dockerfile", ".dockerignore", "gradlew", "build.gradle", "settings.gradle"] : ["Dockerfile", ".dockerignore", "mvnw", "pom.xml"]),
  )
  frontend_files = ["Dockerfile", "prepare.py", "theme.css", "accessibility.js", "nginx.conf"]
  health_path    = var.spring_boot ? "/actuator/health/readiness" : "/api/posts?search=&pageNumber=1&pageSize=5"
}

resource "docker_network" "application" {
  name = var.stack_name
}

module "postgres" {
  source = "../postgres"

  stack_name    = var.stack_name
  network_name  = docker_network.application.name
  database_name = "blog"
  password_file = "${var.secrets_directory}/db_password"
}

resource "docker_image" "backend" {
  name         = "${var.stack_name}-backend:local"
  keep_locally = true
  build {
    context = var.project_directory
    builder = "default"
  }
  triggers = {
    source = sha256(join("", [for name in sort(tolist(local.backend_files)) : "${name}:${filesha256("${var.project_directory}/${name}")}"]))
  }
}

resource "docker_container" "backend" {
  name   = "${var.stack_name}-backend"
  image  = docker_image.backend.image_id
  user   = var.spring_boot ? var.runtime_user : null
  memory = 512
  # Match the RAM limit to disable container swap and avoid daemon-default drift.
  memory_swap  = 512
  restart      = "unless-stopped"
  stop_timeout = 30
  wait         = true
  wait_timeout = 180
  env = concat([
    "DB_URL=jdbc:postgresql://db:5432/blog",
    "DB_USER=blog",
    ], var.spring_boot ? [
    "SPRING_PROFILES_ACTIVE=postgresql", "BLOG_CORS_ORIGINS=${local.origins}",
    ] : [
    "DB_PASSWORD_FILE=/run/secrets/db_password", "CORS_ORIGINS=${local.origins}",
  ])
  networks_advanced {
    name    = docker_network.application.name
    aliases = ["backend"]
  }
  volumes {
    host_path      = "${var.secrets_directory}/db_password"
    container_path = var.spring_boot ? "/run/secrets/spring.datasource.password" : "/run/secrets/db_password"
    read_only      = true
  }
  ports {
    internal = 8080
    external = var.backend_port
    ip       = "127.0.0.1"
  }
  healthcheck {
    test         = ["CMD", "curl", "-fsS", "http://localhost:8080${local.health_path}"]
    interval     = "5s"
    timeout      = "3s"
    retries      = 30
    start_period = "30s"
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  depends_on = [module.postgres]
}

resource "docker_image" "frontend" {
  name         = "${var.stack_name}-frontend:local"
  keep_locally = true
  build {
    context = "${var.project_directory}/frontend"
    builder = "default"
  }
  triggers = {
    source = sha256(join("", [for name in local.frontend_files : "${name}:${filesha256("${var.project_directory}/frontend/${name}")}"]))
  }
}

resource "docker_container" "frontend" {
  name         = "${var.stack_name}-frontend"
  image        = docker_image.frontend.image_id
  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 60
  networks_advanced {
    name = docker_network.application.name
  }
  ports {
    internal = 80
    external = var.frontend_port
    ip       = "127.0.0.1"
  }
  healthcheck {
    test     = ["CMD", "wget", "-q", "--spider", "http://127.0.0.1/"]
    interval = "5s"
    timeout  = "3s"
    retries  = 12
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  depends_on = [docker_container.backend]
}
