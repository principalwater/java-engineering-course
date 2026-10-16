output "deployment" {
  description = "Loopback endpoints and independently managed containers of the two services."
  value = merge({
    name          = var.stack_name
    frontend_url  = var.oauth_enabled ? "https://shop.valgrind-home.localhost:${var.tls_port}" : "http://127.0.0.1:${var.http_port}"
    backend_url   = "http://127.0.0.1:${var.http_port}"
    payments_url  = "http://127.0.0.1:${var.payments_port}"
    analytics_url = var.analytics_enabled ? "http://127.0.0.1:${var.analytics_port}" : null
    containers = merge(
      { for name, container in docker_container.application : name => container.name },
      { backend = docker_container.application["storefront"].name, redis = docker_container.redis.name },
      { for name, database in module.postgres : "${name}_database" => database.container_name },
      var.analytics_enabled ? { clickhouse = docker_container.clickhouse[0].name } : {},
      var.oauth_enabled ? {
        authorization          = docker_container.authorization[0].name
        authorization_database = module.authorization_postgres[0].container_name
        sessions               = docker_container.sessions[0].name
        gateway                = docker_container.gateway[0].name
        mailpit                = docker_container.mailpit[0].name
      } : {},
    )
    # Keys exist only with OAuth, so existing Sprint 7 outputs keep their exact shape.
    }, var.oauth_enabled ? {
    issuer      = local.oauth_issuer
    token_url   = "${local.oauth_issuer}/protocol/openid-connect/token"
    jwk_set_url = "${local.oauth_issuer}/protocol/openid-connect/certs"
    mailbox_url = "http://127.0.0.1:${var.mail_port}"
  } : {})
}
