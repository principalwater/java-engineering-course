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

module "sprint05" {
  source   = "./modules/storefront"
  for_each = contains(["sprint05-postgresql", "sprint05-h2"], var.deployment) ? toset([var.deployment]) : toset([])

  project_directory = "${local.repository}/projects/sprint05-storefront"
  stack_name        = local.stack_name
  profile           = each.key == "sprint05-h2" ? "h2" : "postgresql"
  http_port         = each.key == "sprint05-h2" ? 28086 : 28085
  analytics_port    = each.key == "sprint05-h2" ? 28126 : 28125
  analytics_enabled = var.analytics_enabled
  secrets_directory = var.secrets_directory
  runtime_user      = var.runtime_user
}

module "sprint06" {
  source   = "./modules/storefront"
  for_each = contains(["sprint06-postgresql", "sprint06-h2"], var.deployment) ? toset([var.deployment]) : toset([])

  project_directory          = "${local.repository}/projects/sprint06-reactive-storefront"
  stack_name                 = local.stack_name
  profile                    = each.key == "sprint06-h2" ? "h2" : "postgresql"
  database_protocol          = "r2dbc"
  database_password_property = "spring.r2dbc.password"
  session_cookie_property    = "server.reactive.session.cookie.name"
  http_port                  = each.key == "sprint06-h2" ? 28088 : 28087
  analytics_port             = each.key == "sprint06-h2" ? 28128 : 28127
  analytics_enabled          = var.analytics_enabled
  secrets_directory          = var.secrets_directory
  runtime_user               = var.runtime_user
}

module "sprint07" {
  source   = "./modules/rest-storefront"
  for_each = contains(["sprint07-postgresql", "sprint07-h2"], var.deployment) ? toset([var.deployment]) : toset([])

  project_directory = "${local.repository}/projects/sprint07-rest-service"
  stack_name        = local.stack_name
  profile           = each.key == "sprint07-h2" ? "h2" : "postgresql"
  artifact_hashes   = var.deployment_artifact_hashes
  http_port         = each.key == "sprint07-h2" ? 28090 : 28089
  payments_port     = each.key == "sprint07-h2" ? 29090 : 29089
  analytics_port    = each.key == "sprint07-h2" ? 28130 : 28129
  analytics_enabled = var.analytics_enabled
  secrets_directory = var.secrets_directory
  runtime_user      = var.runtime_user
}

module "sprint08" {
  source   = "./modules/rest-storefront"
  for_each = contains(["sprint08-postgresql", "sprint08-h2"], var.deployment) ? toset([var.deployment]) : toset([])

  project_directory  = "${local.repository}/projects/sprint08-security"
  stack_name         = local.stack_name
  profile            = each.key == "sprint08-h2" ? "h2" : "postgresql"
  artifact_hashes    = var.deployment_artifact_hashes
  oauth_enabled      = true
  authorization_port = each.key == "sprint08-h2" ? 28382 : 28381
  network_subnet     = each.key == "sprint08-h2" ? "10.251.8.32/27" : "10.251.8.0/27"
  http_port          = each.key == "sprint08-h2" ? 28092 : 28091
  payments_port      = each.key == "sprint08-h2" ? 29092 : 29091
  analytics_port     = each.key == "sprint08-h2" ? 28132 : 28131
  analytics_enabled  = var.analytics_enabled
  secrets_directory  = var.secrets_directory
  runtime_user       = var.runtime_user
}
