output "deployment" {
  description = "Loopback endpoints and managed containers for operational checks."
  value = {
    name          = var.stack_name
    frontend_url  = "http://127.0.0.1:${var.frontend_port}"
    backend_url   = "http://127.0.0.1:${var.backend_port}"
    analytics_url = var.analytics_enabled ? "http://127.0.0.1:${var.analytics_port}" : null
    containers = merge({
      database = module.postgres.container_name
      backend  = docker_container.backend.name
      frontend = docker_container.frontend.name
    }, var.analytics_enabled ? { clickhouse = docker_container.clickhouse[0].name } : {})
  }
}
