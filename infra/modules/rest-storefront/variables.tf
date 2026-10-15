variable "project_directory" {
  description = "Root of the existing two-service Gradle project."
  type        = string
}
variable "stack_name" {
  description = "Unique prefix for this isolated deployment."
  type        = string
}
variable "profile" {
  description = "Native database implementation of both services."
  type        = string
  validation {
    condition     = contains(["postgresql", "h2"], var.profile)
    error_message = "Select the postgresql or h2 profile."
  }
}
variable "artifact_hashes" {
  description = "SHA-256 of each prepared build/deployment executable JAR."
  type        = map(string)
  validation {
    condition = toset(keys(var.artifact_hashes)) == toset(["storefront", "payments"]) && alltrue([
      for digest in values(var.artifact_hashes) : can(regex("^[a-f0-9]{64}$", digest))
    ])
    error_message = "Provide SHA-256 hashes for exactly storefront and payments."
  }
}
variable "http_port" {
  description = "Loopback storefront HTTP port."
  type        = number
}
variable "payments_port" {
  description = "Loopback payments HTTP port protected by the selected bearer authentication."
  type        = number
}
variable "analytics_port" {
  description = "Loopback ClickHouse HTTP port."
  type        = number
}
variable "analytics_enabled" {
  description = "Enable optional paid-order analytics."
  type        = bool
}
variable "oauth_enabled" {
  description = "Replace the shared service token with a standalone Keycloak authorization server and its own PostgreSQL."
  type        = bool
  default     = false
}
variable "authorization_port" {
  description = "Loopback Keycloak HTTP port; it is also the canonical public issuer port."
  type        = number
  default     = null
  validation {
    condition     = !var.oauth_enabled || var.authorization_port != null
    error_message = "Provide authorization_port when OAuth is enabled."
  }
}
variable "secrets_directory" {
  description = "External file paths only; secret contents never enter state."
  type        = string
}
variable "runtime_user" {
  description = "Host UID:GID for reading mode-0600 secret mounts."
  type        = string
}

variable "network_subnet" {
  description = "Optional small private CIDR; null retains Docker IPAM allocation for existing stacks."
  type        = string
  default     = null
  validation {
    condition     = var.network_subnet == null || can(cidrnetmask(var.network_subnet))
    error_message = "network_subnet must be a valid IPv4 CIDR or null."
  }
}
