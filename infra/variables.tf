variable "deployment" {
  description = "One independently managed local stack."
  type        = string
  validation {
    condition     = contains(["sprint03", "sprint04", "sprint05-postgresql", "sprint05-h2", "docker-exercise", "mvc-exercise"], var.deployment)
    error_message = "Select an implemented project or exercise deployment."
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
  description = "Enable the selected project's optional ClickHouse integration."
  type        = bool
  default     = false
}
