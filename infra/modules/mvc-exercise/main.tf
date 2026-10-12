terraform {
  required_providers {
    docker = { source = "registry.terraform.io/kreuzwerker/docker" }
  }
}

variable "project_directory" {
  description = "Existing MVC exercise with a locally packaged WAR."
  type        = string
}
variable "stack_name" {
  description = "Unique stack prefix."
  type        = string
}
variable "http_port" {
  description = "Loopback Tomcat port."
  type        = number
}

resource "docker_image" "tomcat" {
  name         = "tomcat:10.1-jdk21-temurin@sha256:887953afba0434879427f18d011d4df26d49e1f5c9aad18b779ed738edf22cf3"
  keep_locally = true
}

resource "docker_container" "web" {
  name         = var.stack_name
  image        = docker_image.tomcat.image_id
  memory       = 512
  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 120
  # A changed bind-mounted WAR needs a fresh servlet deployment, not just a new plan.
  env = ["COURSE_ARTIFACT_SHA256=${try(filesha256("${var.project_directory}/target/spring-web-demo.war"), "not-packaged")}"]
  ports {
    internal = 8080
    external = var.http_port
    ip       = "127.0.0.1"
  }
  volumes {
    host_path      = "${var.project_directory}/target/spring-web-demo.war"
    container_path = "/usr/local/tomcat/webapps/spring-web-demo.war"
    read_only      = true
  }
  healthcheck {
    test     = ["CMD", "curl", "-fsS", "http://localhost:8080/spring-web-demo/home"]
    interval = "3s"
    timeout  = "3s"
    retries  = 40
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  lifecycle {
    precondition {
      condition     = fileexists("${var.project_directory}/target/spring-web-demo.war")
      error_message = "Package spring-web-demo.war with the repository Maven wrapper first."
    }
  }
}

output "deployment" {
  description = "Loopback endpoint for the existing servlet/H2 exercise."
  value = {
    name          = var.stack_name
    frontend_url  = "http://127.0.0.1:${var.http_port}/spring-web-demo"
    backend_url   = null
    analytics_url = null
    containers    = { backend = docker_container.web.name }
  }
}
