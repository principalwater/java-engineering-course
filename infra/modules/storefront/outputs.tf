output "deployment" {
  description = "Embedded server endpoints and independently managed service names."
  value = {
    name          = var.stack_name
    frontend_url  = "http://127.0.0.1:${var.http_port}"
    backend_url   = "http://127.0.0.1:${var.http_port}"
    analytics_url = var.analytics_enabled ? "http://127.0.0.1:${var.analytics_port}" : null
    containers = merge(
      { backend = docker_container.application.name },
      local.persistent ? { database = module.postgres[0].container_name } : {},
      var.analytics_enabled ? { clickhouse = docker_container.clickhouse[0].name } : {},
    )
  }
}
