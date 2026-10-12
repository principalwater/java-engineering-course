variable "deployment" {
  description = "One independently managed local stack."
  type        = string
  validation {
    condition     = contains(["sprint03", "sprint04", "docker-exercise", "mvc-exercise"], var.deployment)
    error_message = "Select sprint03, sprint04, docker-exercise or mvc-exercise."
  }
}

variable "docker_host" {
  description = "Docker Engine endpoint from the active local Docker context."
  type        = string
  validation {
    condition     = startswith(var.docker_host, "unix://") || startswith(var.docker_host, "npipe://")
    error_message = "This configuration supports only a local Docker Engine."
  }
}

variable "secrets_directory" {
  description = "Absolute directory of externally initialized secret files; never secret values."
  type        = string
}

variable "runtime_user" {
  description = "Host UID:GID, required to read mode-0600 bind-mounted secrets on Linux."
  type        = string
  validation {
    condition     = can(regex("^[0-9]+:[0-9]+$", var.runtime_user))
    error_message = "Use a numeric UID:GID pair."
  }
}

variable "analytics_enabled" {
  description = "Enable the existing read-only ClickHouse integration for blog projects."
  type        = bool
  default     = false
}
