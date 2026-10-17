resource "docker_container" "platform" {
  for_each = local.platform

  name          = "${var.stack_name}-${each.key}"
  image         = docker_image.base[each.key].image_id
  entrypoint    = try(each.value.entrypoint, null)
  command       = try(each.value.command, null)
  user          = contains(["authorization", "edge", "accounts", "cash", "transfer", "notifications", "gateway", "front", "analytics"], each.key) ? var.runtime_user : try(each.value.user, null)
  memory        = local.memory_mb[each.key]
  memory_swap   = local.memory_mb[each.key]
  cpus          = format("%.1f", each.value.cpus)
  read_only     = try(each.value.read_only, false)
  stop_timeout  = tonumber(trimsuffix(try(each.value.stop_grace_period, "30s"), "s"))
  restart       = each.value.restart
  security_opts = try(each.value.security_opt, [])
  tmpfs         = { for mount in try(each.value.tmpfs, []) : split(":", mount)[0] => split(":", mount)[1] }
  env = concat([for key, value in try(each.value.environment, {}) : "${key}=${replace(tostring(value), ".localhost:8449", ".localhost:${var.tls_port}")}"], [
    "COURSE_CONFIG_SHA256=${local.configuration_hashes[each.key]}",
  ])
  networks_advanced {
    name    = docker_network.application.name
    aliases = concat([each.key], each.key == "edge" ? ["bank.valgrind-home.localhost", "auth-bank.valgrind-home.localhost"] : [])
  }
  dynamic "volumes" {
    for_each = local.file_mounts[each.key]
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  dynamic "volumes" {
    for_each = local.data_mounts[each.key]
    content {
      volume_name    = docker_volume.data[volumes.value].name
      container_path = volumes.key
    }
  }
  dynamic "capabilities" {
    for_each = length(try(each.value.cap_drop, [])) == 0 ? [] : [each.value.cap_drop]
    content { drop = capabilities.value }
  }
  dynamic "ulimit" {
    for_each = try(each.value.ulimits, {})
    content {
      name = ulimit.key
      soft = ulimit.value.soft
      hard = ulimit.value.hard
    }
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
}

resource "docker_container" "authorization" {
  for_each = { authorization = local.services.authorization }

  name          = "${var.stack_name}-${each.key}"
  image         = docker_image.authorization.image_id
  entrypoint    = try(each.value.entrypoint, null)
  command       = try(each.value.command, null)
  user          = contains(["authorization", "edge", "accounts", "cash", "transfer", "notifications", "gateway", "front", "analytics"], each.key) ? var.runtime_user : try(each.value.user, null)
  memory        = local.memory_mb[each.key]
  memory_swap   = local.memory_mb[each.key]
  cpus          = format("%.1f", each.value.cpus)
  read_only     = try(each.value.read_only, false)
  stop_timeout  = tonumber(trimsuffix(try(each.value.stop_grace_period, "30s"), "s"))
  restart       = each.value.restart
  security_opts = try(each.value.security_opt, [])
  tmpfs         = { for mount in try(each.value.tmpfs, []) : split(":", mount)[0] => split(":", mount)[1] }
  env = concat([for key, value in try(each.value.environment, {}) : "${key}=${replace(tostring(value), ".localhost:8449", ".localhost:${var.tls_port}")}"], [
    "COURSE_CONFIG_SHA256=${local.configuration_hashes[each.key]}",
  ])
  networks_advanced {
    name    = docker_network.application.name
    aliases = concat([each.key], each.key == "edge" ? ["bank.valgrind-home.localhost", "auth-bank.valgrind-home.localhost"] : [])
  }
  dynamic "volumes" {
    for_each = local.file_mounts[each.key]
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  dynamic "volumes" {
    for_each = local.data_mounts[each.key]
    content {
      volume_name    = docker_volume.data[volumes.value].name
      container_path = volumes.key
    }
  }
  dynamic "capabilities" {
    for_each = length(try(each.value.cap_drop, [])) == 0 ? [] : [each.value.cap_drop]
    content { drop = capabilities.value }
  }
  dynamic "ulimit" {
    for_each = try(each.value.ulimits, {})
    content {
      name = ulimit.key
      soft = ulimit.value.soft
      hard = ulimit.value.hard
    }
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  depends_on = [docker_container.platform]
}

resource "docker_container" "initializer" {
  for_each = local.initializers

  name          = "${var.stack_name}-${each.key}"
  image         = docker_image.base[each.key == "config-seed" ? "discovery" : trimsuffix(each.key, "-init")].image_id
  entrypoint    = try(each.value.entrypoint, null)
  command       = try(each.value.command, null)
  user          = contains(["authorization", "edge", "accounts", "cash", "transfer", "notifications", "gateway", "front", "analytics"], each.key) ? var.runtime_user : try(each.value.user, null)
  memory        = local.memory_mb[each.key]
  memory_swap   = local.memory_mb[each.key]
  cpus          = format("%.1f", each.value.cpus)
  read_only     = try(each.value.read_only, false)
  stop_timeout  = tonumber(trimsuffix(try(each.value.stop_grace_period, "30s"), "s"))
  restart       = each.value.restart
  security_opts = try(each.value.security_opt, [])
  tmpfs         = { for mount in try(each.value.tmpfs, []) : split(":", mount)[0] => split(":", mount)[1] }
  env = concat([for key, value in try(each.value.environment, {}) : "${key}=${replace(tostring(value), ".localhost:8449", ".localhost:${var.tls_port}")}"], [
    "COURSE_CONFIG_SHA256=${local.configuration_hashes[each.key]}",
  ])
  networks_advanced {
    name    = docker_network.application.name
    aliases = concat([each.key], each.key == "edge" ? ["bank.valgrind-home.localhost", "auth-bank.valgrind-home.localhost"] : [])
  }
  dynamic "volumes" {
    for_each = local.file_mounts[each.key]
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  dynamic "volumes" {
    for_each = local.data_mounts[each.key]
    content {
      volume_name    = docker_volume.data[volumes.value].name
      container_path = volumes.key
    }
  }
  dynamic "capabilities" {
    for_each = length(try(each.value.cap_drop, [])) == 0 ? [] : [each.value.cap_drop]
    content { drop = capabilities.value }
  }
  dynamic "ulimit" {
    for_each = try(each.value.ulimits, {})
    content {
      name = ulimit.key
      soft = ulimit.value.soft
      hard = ulimit.value.hard
    }
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }

  attach   = true
  must_run = false
  logs     = false
  timeouts { create = "10m" }
  lifecycle {
    postcondition {
      condition     = self.exit_code == 0
      error_message = "The finite bank initializer failed; inspect its redacted diagnostics."
    }
  }
  depends_on = [docker_container.platform, docker_container.authorization]
}

resource "docker_container" "application" {
  for_each = local.applications

  name          = "${var.stack_name}-${each.key}"
  image         = docker_image.application[each.key].image_id
  entrypoint    = try(each.value.entrypoint, null)
  command       = try(each.value.command, null)
  user          = contains(["authorization", "edge", "accounts", "cash", "transfer", "notifications", "gateway", "front", "analytics"], each.key) ? var.runtime_user : try(each.value.user, null)
  memory        = local.memory_mb[each.key]
  memory_swap   = local.memory_mb[each.key]
  cpus          = format("%.1f", each.value.cpus)
  read_only     = try(each.value.read_only, false)
  stop_timeout  = tonumber(trimsuffix(try(each.value.stop_grace_period, "30s"), "s"))
  restart       = each.value.restart
  security_opts = try(each.value.security_opt, [])
  tmpfs         = { for mount in try(each.value.tmpfs, []) : split(":", mount)[0] => split(":", mount)[1] }
  env = concat([for key, value in try(each.value.environment, {}) : "${key}=${replace(tostring(value), ".localhost:8449", ".localhost:${var.tls_port}")}"], [
    "COURSE_CONFIG_SHA256=${local.configuration_hashes[each.key]}",
  ])
  networks_advanced {
    name    = docker_network.application.name
    aliases = concat([each.key], each.key == "edge" ? ["bank.valgrind-home.localhost", "auth-bank.valgrind-home.localhost"] : [])
  }
  dynamic "volumes" {
    for_each = local.file_mounts[each.key]
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  dynamic "volumes" {
    for_each = local.data_mounts[each.key]
    content {
      volume_name    = docker_volume.data[volumes.value].name
      container_path = volumes.key
    }
  }
  dynamic "capabilities" {
    for_each = length(try(each.value.cap_drop, [])) == 0 ? [] : [each.value.cap_drop]
    content { drop = capabilities.value }
  }
  dynamic "ulimit" {
    for_each = try(each.value.ulimits, {})
    content {
      name = ulimit.key
      soft = ulimit.value.soft
      hard = ulimit.value.hard
    }
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }
  depends_on = [docker_container.initializer, docker_container.authorization]
}

resource "docker_container" "edge" {
  for_each = { edge = local.services.edge }

  name          = "${var.stack_name}-${each.key}"
  image         = docker_image.edge.image_id
  entrypoint    = try(each.value.entrypoint, null)
  command       = try(each.value.command, null)
  user          = contains(["authorization", "edge", "accounts", "cash", "transfer", "notifications", "gateway", "front", "analytics"], each.key) ? var.runtime_user : try(each.value.user, null)
  memory        = local.memory_mb[each.key]
  memory_swap   = local.memory_mb[each.key]
  cpus          = format("%.1f", each.value.cpus)
  read_only     = try(each.value.read_only, false)
  stop_timeout  = tonumber(trimsuffix(try(each.value.stop_grace_period, "30s"), "s"))
  restart       = each.value.restart
  security_opts = try(each.value.security_opt, [])
  tmpfs         = { for mount in try(each.value.tmpfs, []) : split(":", mount)[0] => split(":", mount)[1] }
  env = concat([for key, value in try(each.value.environment, {}) : "${key}=${replace(tostring(value), ".localhost:8449", ".localhost:${var.tls_port}")}"], [
    "COURSE_CONFIG_SHA256=${local.configuration_hashes[each.key]}",
  ])
  networks_advanced {
    name    = docker_network.application.name
    aliases = concat([each.key], each.key == "edge" ? ["bank.valgrind-home.localhost", "auth-bank.valgrind-home.localhost"] : [])
  }
  dynamic "volumes" {
    for_each = local.file_mounts[each.key]
    content {
      host_path      = volumes.value
      container_path = volumes.key
      read_only      = true
    }
  }
  dynamic "volumes" {
    for_each = local.data_mounts[each.key]
    content {
      volume_name    = docker_volume.data[volumes.value].name
      container_path = volumes.key
    }
  }
  dynamic "capabilities" {
    for_each = length(try(each.value.cap_drop, [])) == 0 ? [] : [each.value.cap_drop]
    content { drop = capabilities.value }
  }
  dynamic "ulimit" {
    for_each = try(each.value.ulimits, {})
    content {
      name = ulimit.key
      soft = ulimit.value.soft
      hard = ulimit.value.hard
    }
  }
  labels {
    label = "dev.principalwater.deployment"
    value = var.stack_name
  }

  ports {
    internal = var.tls_port
    external = var.tls_port
    ip       = "127.0.0.1"
  }
  depends_on = [docker_container.application]
}
