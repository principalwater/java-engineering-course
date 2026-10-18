terraform {
  required_providers {
    docker = { source = "registry.terraform.io/kreuzwerker/docker" }
  }
}

locals {
  core            = jsondecode(file("${var.runtime_directory}/compose.json"))
  optional        = jsondecode(file("${var.runtime_directory}/compose.analytics.json"))
  active_optional = { for name, service in local.optional.services : name => service if var.analytics_enabled }
  services = { for name in setunion(toset(keys(local.core.services)), toset(keys(local.active_optional))) : name => merge(
    try(local.core.services[name], {}), try(local.active_optional[name], {}), {
      environment = merge(try(local.core.services[name].environment, {}), try(local.active_optional[name].environment, {}))
    }
  ) }
  application_names = toset(concat(["accounts", "cash", "transfer", "notifications", "gateway", "front"], var.analytics_enabled ? ["analytics"] : []))
  applications      = { for name in local.application_names : name => local.services[name] }
  platform          = { for name in concat(["database", "discovery"], var.analytics_enabled ? ["cassandra", "clickhouse", "kafka"] : []) : name => local.services[name] }
  initializers      = { for name in concat(["config-seed"], var.analytics_enabled ? ["cassandra-init", "clickhouse-init", "kafka-init"] : []) : name => local.services[name] }
  base_images       = { for name in concat(["database", "discovery"], var.analytics_enabled ? ["cassandra", "clickhouse", "kafka"] : []) : name => local.services[name].image }
  # Read declarations only; no Compose subprocess or second source of application settings.
  dependency_stage   = merge({ for name in keys(local.platform) : name => 0 }, { authorization = 1 }, { for name in keys(local.initializers) : name => 2 }, { for name in local.application_names : name => 3 }, { edge = 4 })
  dependencies       = { for name, service in local.services : name => try(tolist(service.depends_on), keys(service.depends_on), []) }
  supported_fields   = toset(["image", "build", "user", "environment", "secrets", "volumes", "tmpfs", "read_only", "security_opt", "cap_drop", "restart", "stop_grace_period", "mem_limit", "memswap_limit", "cpus", "depends_on", "entrypoint", "command", "ports", "networks", "ulimits"])
  unsupported_fields = flatten([for name, service in local.services : [for field in setsubtract(toset(keys(service)), local.supported_fields) : "${name}.${field}"]])
  file_mounts = { for name, service in local.services : name => merge(
    { for secret in try(service.secrets, []) : "/run/secrets/${try(secret.target, secret.source, secret)}" => "${var.secrets_directory}/${try(secret.source, secret)}" },
    { for mount in try(service.volumes, []) : split(":", mount)[1] => (
      split(":", mount)[1] == "/setup/bank-realm.json" ? "${var.runtime_directory}/bank-realm.json" :
      split(":", mount)[1] == "/etc/caddy/Caddyfile" ? "${var.runtime_directory}/Caddyfile" :
      abspath("${var.project_directory}/${split(":", mount)[0]}")
    ) if startswith(mount, "./") }
  ) }
  data_mounts = { for name, service in local.services : name => {
    for mount in try(service.volumes, []) : split(":", mount)[1] => split(":", mount)[0] if !startswith(mount, "./")
  } }
  memory_mb = { for name, service in local.services : name => (
    endswith(lower(service.mem_limit), "g") ? tonumber(trimsuffix(lower(service.mem_limit), "g")) * 1024 : tonumber(trimsuffix(lower(service.mem_limit), "m"))
  ) }
  configuration_hashes = { for name, service in local.services : name => sha256(join("", [
    for mount in try(service.volumes, []) : filesha256("${var.project_directory}/${split(":", mount)[0]}") if startswith(mount, "./")
  ])) }
}

resource "docker_network" "application" {
  name = var.stack_name
  ipam_config {
    subnet      = var.network_subnet
    gateway     = cidrhost(var.network_subnet, 1)
    ip_range    = ""
    aux_address = {}
  }
  lifecycle {
    precondition {
      condition = alltrue([for name, service in local.optional.services :
        !contains(keys(local.core.services), name) || toset(keys(service)) == toset(["environment"])
      ])
      error_message = "Existing bank services support environment-only analytics overrides; other Compose merge semantics must be mapped explicitly."
    }
    precondition {
      condition = alltrue([for name, dependencies in local.dependencies : alltrue([
        for dependency in dependencies : try(local.dependency_stage[dependency] < local.dependency_stage[name], false)
        ])]) && alltrue([for name, service in local.services : alltrue([
        for dependency, rule in try(tomap(service.depends_on), {}) : (
          toset(keys(rule)) == toset(["condition"]) && contains(["service_started", "service_completed_successfully"], rule.condition) &&
          (rule.condition != "service_completed_successfully" || contains(keys(local.initializers), dependency))
        )
      ])])
      error_message = "Bank dependencies must follow storage, authorization, finite initialization, application and edge stages; Docker healthcheck dependencies are deferred."
    }
    precondition {
      condition = alltrue([for document in [local.core, local.optional] : alltrue([
        for name, declaration in try(document.volumes, {}) : contains(["bank-data", "discovery-data", "discovery-transactions", "cassandra-data", "analytics-hot", "analytics-cold", "kafka-data"], name) && (declaration == null || try(length(keys(declaration)) == 0, false))
        ])]) && alltrue([for name, declaration in try(local.core.networks, {}) : name == "default" && try(
        toset(keys(declaration)) == toset(["ipam"]) && toset(keys(declaration.ipam)) == toset(["config"]) && length(declaration.ipam.config) == 1 && toset(keys(declaration.ipam.config[0])) == toset(["subnet"]), false
      )])
      error_message = "Bank named volumes use the native protected storage set; the source default subnet is replaced by the isolated native subnet."
    }
    precondition {
      condition = alltrue([for document in [local.core, local.optional] : alltrue([
        for name, declaration in try(document.secrets, {}) : toset(keys(declaration)) == toset(["file"]) && endswith(declaration.file, "/${name}")
        ])]) && alltrue([for name, service in local.services : alltrue([
        for network, configuration in try(service.networks, {}) : name == "edge" && network == "default" && toset(keys(configuration)) == toset(["aliases"]) &&
        toset(configuration.aliases) == toset(["bank.valgrind-home.localhost", "auth-bank.valgrind-home.localhost"])
      ])])
      error_message = "Bank declarations support external file secrets and the canonical edge aliases only."
    }
    precondition {
      condition = toset(keys(local.services)) == setunion(toset(keys(local.platform)), toset(keys(local.initializers)), local.application_names, toset(["authorization", "edge"])) && alltrue([
        for document in [local.core, local.optional] : alltrue([for field in keys(document) : contains(["name", "services", "volumes", "secrets", "networks"], field) || startswith(field, "x-")])
      ])
      error_message = "Only the bank services and supported top-level declarations may be mapped to native Docker resources."
    }
    precondition {
      condition = alltrue([for name, service in local.services : (
        contains(["authorization", "edge"], name) ? (try(service.build == "./${name}", false) || try(service.build.context == "./${name}" && toset(keys(service.build)) == toset(["context"]), false)) : (
          contains(local.application_names, name) ? try(service.build.context == "." && service.build.args == { SERVICE = name }, false) : !can(service.build)
        )
      )]) && alltrue([for name, image in local.base_images : can(regex("@sha256:[a-f0-9]{64}$", image))])
      error_message = "Use the existing bank Dockerfiles, SERVICE-only Java build arguments, and digest-pinned official images."
    }
    precondition {
      condition = alltrue([for name, service in local.services : alltrue([
        for secret in try(service.secrets, []) : (
          can(regex("^[a-z0-9][a-z0-9_.-]*$", try(secret.source, secret))) &&
          can(regex("^[a-z0-9][a-z0-9_.-]*$", try(secret.target, secret.source, secret))) &&
          length(setsubtract(toset(try(keys(secret), [])), toset(["source", "target"]))) == 0
        )
      ])]) && local.services.edge.ports == ["127.0.0.1:8449:8449"]
      error_message = "Secret mounts require simple external filenames and supported targets; only the canonical loopback TLS entry may publish a port."
    }
    precondition {
      condition = length(local.unsupported_fields) == 0 && alltrue([for name, service in local.services : (
        service.mem_limit == service.memswap_limit &&
        (name == "edge" || length(try(service.ports, [])) == 0) &&
        alltrue([for mount in try(service.volumes, []) : startswith(mount, "./") ? length(split(":", mount)) == 3 && endswith(mount, ":ro") && !contains(split("/", split(":", mount)[0]), "..") : length(split(":", mount)) == 2]) &&
        alltrue([for setting in keys(try(service.environment, {})) : !can(regex("(PASSWORD|SECRET|TOKEN|SASL_JAAS_CONFIG)$", setting))]) &&
        length(setsubtract(toset(try(keys(service.build), [])), toset(["context", "args"]))) == 0 &&
        length(setsubtract(toset(keys(try(service.ulimits, {}))), toset(["nofile"]))) == 0 && alltrue([
          for limit in values(try(service.ulimits, {})) : toset(keys(limit)) == toset(["soft", "hard"])
        ])
      )])
      error_message = "The bank source contains unsupported fields, ports, mounts, secret-bearing environment settings or unbounded resource limits."
    }
  }
}

resource "docker_volume" "data" {
  # Volume identity is stable when the optional analytics processes are disabled.
  for_each = toset(["bank-data", "discovery-data", "discovery-transactions", "cassandra-data", "analytics-hot", "analytics-cold", "kafka-data"])
  name     = "${var.stack_name}-${each.key}"
  lifecycle { prevent_destroy = true }
}

resource "docker_image" "base" {
  for_each     = local.base_images
  name         = each.value
  keep_locally = true
}

resource "docker_image" "application" {
  for_each     = local.applications
  name         = "${var.stack_name}-${each.key}:local"
  keep_locally = true
  build {
    context    = var.project_directory
    builder    = "default"
    build_args = { SERVICE = each.key }
  }
  triggers = {
    artifact   = var.artifact_hashes[each.key]
    dockerfile = filesha256("${var.project_directory}/Dockerfile")
    context    = filesha256("${var.project_directory}/.dockerignore")
    security   = filesha256("${var.project_directory}/config/jvm-network.security")
  }
}

resource "docker_image" "authorization" {
  name         = "${var.stack_name}-authorization:local"
  keep_locally = true
  build {
    context = "${var.project_directory}/authorization"
    builder = "default"
  }
  triggers = { dockerfile = filesha256("${var.project_directory}/authorization/Dockerfile") }
}

resource "docker_image" "edge" {
  name         = "${var.stack_name}-edge:local"
  keep_locally = true
  build {
    context = "${var.project_directory}/edge"
    builder = "default"
  }
  triggers = { dockerfile = filesha256("${var.project_directory}/edge/Dockerfile") }
}
