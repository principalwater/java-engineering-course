module "authorization_postgres" {
  source = "../postgres"
  count  = var.oauth_enabled ? 1 : 0

  stack_name      = "${var.stack_name}-authorization"
  network_name    = docker_network.application.name
  network_aliases = ["authorization-db"]
  database_name   = "authorization"
  password_file   = "${var.secrets_directory}/authorization_db_password"
  tcp_healthcheck = true
  memory_mb       = 256
  cpus            = "1.0"
}

resource "docker_image" "authorization" {
  count        = var.oauth_enabled ? 1 : 0
  name         = "${var.stack_name}-authorization:local"
  keep_locally = true
  build {
    context = local.authorization_directory
    builder = "default"
  }
  triggers = {
    dockerfile = filesha256("${local.authorization_directory}/Dockerfile")
  }
}

resource "docker_container" "authorization" {
  count = var.oauth_enabled ? 1 : 0

  name         = "${var.stack_name}-authorization"
  image        = docker_image.authorization[0].image_id
  user         = var.runtime_user
  memory       = 768
  memory_swap  = 768
  cpus         = "2.0"
  read_only    = true
  stop_timeout = 30
  restart      = "unless-stopped"
  wait         = true
  wait_timeout = 240
  # The optimized image only needs scratch space; the realm file is a read-only child of the data tmpfs.
  tmpfs = {
    "/tmp"               = "rw,noexec,nosuid,size=64m,mode=1777"
    "/opt/keycloak/data" = "rw,noexec,nosuid,size=32m,mode=1777"
  }
  # Passwords and the client secret are files; start-authorization.sh exports them inside the process only.
  env = [
    "KC_BOOTSTRAP_ADMIN_USERNAME=admin",
    "KC_HOSTNAME=https://auth.valgrind-home.localhost:${var.tls_port}",
    "KC_PROXY_HEADERS=xforwarded",
    "KC_HTTP_ENABLED=true",
    "KC_DB=postgres",
    "KC_DB_URL=jdbc:postgresql://authorization-db:5432/authorization",
    "KC_DB_USERNAME=authorization",
    "KC_CACHE=local",
    "JAVA_OPTS_APPEND=-XX:ActiveProcessorCount=2",
    # Bind-mounted file edits are invisible to the provider; this hash replaces the container when they change.
    "COURSE_CONFIG_SHA256=${sha256(join("", [for name in ["start-authorization.sh", "storefront-api-realm.json"] : filesha256("${local.authorization_directory}/${name}")]))}",
  ]
  networks_advanced {
    name    = docker_network.application.name
    aliases = ["authorization"]
  }
  ports {
    internal = 8080
    external = var.authorization_port
    ip       = "127.0.0.1"
  }
  dynamic "volumes" {
    for_each = {
      "/run/secrets/authorization_db_password"              = "${var.secrets_directory}/authorization_db_password"
      "/run/secrets/authorization_admin_password"           = "${var.secrets_directory}/authorization_admin_password"
      "/run/secrets/oauth_client_secret"                    = "${var.secrets_directory}/oauth_client_secret"
      "/setup/start-authorization.sh"                       = "${local.authorization_directory}/start-authorization.sh"
      "/opt/keycloak/data/import/storefront-api-realm.json" = "${local.authorization_directory}/storefront-api-realm.json"
    }
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  healthcheck {
    # Keycloak serves readiness on its management port 9000; the image has no curl.
    test = [
      "CMD", "bash", "-ec",
      "exec 3<>/dev/tcp/127.0.0.1/9000; printf 'GET /health/ready HTTP/1.0\\r\\n\\r\\n' >&3; read -r first <&3; [[ \"$first\" == *' 200 '* ]]",
    ]
    interval     = "3s"
    timeout      = "3s"
    retries      = 60
    start_period = "30s"
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  depends_on = [module.authorization_postgres]
}

locals {
  authorization_directory = "${var.project_directory}/authorization"
}
