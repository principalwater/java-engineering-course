locals {
  repository = abspath("${path.module}/..")
  stack_name = "java-tofu-${var.deployment}"
}

module "sprint03" {
  source = "./modules/blog"
  count  = var.deployment == "sprint03" ? 1 : 0

  project_directory = "${local.repository}/projects/sprint03-spring-blog"
  stack_name        = local.stack_name
  spring_boot       = false
  frontend_port     = 28083
  backend_port      = 29083
  analytics_port    = 28123
  analytics_enabled = var.analytics_enabled
  secrets_directory = var.secrets_directory
  runtime_user      = var.runtime_user
}

module "sprint04" {
  source = "./modules/blog"
  count  = var.deployment == "sprint04" ? 1 : 0

  project_directory = "${local.repository}/projects/sprint04-spring-boot-blog"
  stack_name        = local.stack_name
  spring_boot       = true
  frontend_port     = 28084
  backend_port      = 29084
  analytics_port    = 28124
  analytics_enabled = var.analytics_enabled
  secrets_directory = var.secrets_directory
  runtime_user      = var.runtime_user
}

module "docker_exercise" {
  source = "./modules/docker-exercise"
  count  = var.deployment == "docker-exercise" ? 1 : 0

  project_directory = "${local.repository}/exercises/supplements/docker"
  stack_name        = local.stack_name
  http_port         = 28380
  secrets_directory = var.secrets_directory
  runtime_user      = var.runtime_user
}

module "mvc_exercise" {
  source = "./modules/mvc-exercise"
  count  = var.deployment == "mvc-exercise" ? 1 : 0

  project_directory = "${local.repository}/exercises/sprint03/spring-mvc-jdbc"
  stack_name        = local.stack_name
  http_port         = 28081
}
