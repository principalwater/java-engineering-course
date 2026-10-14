output "deployment" {
  description = "Loopback endpoints and independently managed containers of the two services."
  value = {
    name          = var.stack_name
    frontend_url  = "http://127.0.0.1:${var.http_port}"
    backend_url   = "http://127.0.0.1:${var.http_port}"
    payments_url  = "http://127.0.0.1:${var.payments_port}"
    analytics_url = var.analytics_enabled ? "http://127.0.0.1:${var.analytics_port}" : null
    containers = merge(
      { for name, container in docker_container.application : name => container.name },
      { backend = docker_container.application["storefront"].name, redis = docker_container.redis.name },
      { for name, database in module.postgres : "${name}_database" => database.container_name },
      var.analytics_enabled ? { clickhouse = docker_container.clickhouse[0].name } : {},
    )
  }
}
