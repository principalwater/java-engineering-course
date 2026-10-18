variable "project_directory" {
  description = "Absolute path to the existing bank Gradle project, including its native deployment declarations."
  type        = string
}
variable "runtime_directory" {
  description = "External directory containing the public realm and Caddy templates rendered for this isolated TLS port."
  type        = string
}
variable "stack_name" {
  description = "Unique prefix for this native bank deployment."
  type        = string
}
variable "artifact_hashes" {
  description = "SHA-256 hashes of all seven Gradle packageDeployment JARs."
  type        = map(string)
  validation {
    condition = toset(keys(var.artifact_hashes)) == toset(["accounts", "cash", "transfer", "notifications", "gateway", "front", "analytics"]) && alltrue([
      for digest in values(var.artifact_hashes) : can(regex("^[a-f0-9]{64}$", digest))
    ])
    error_message = "Provide SHA-256 hashes for the seven bank service JARs."
  }
}
variable "secrets_directory" {
  description = "External managed file source; secret contents are never read by OpenTofu."
  type        = string
}
variable "runtime_user" {
  description = "Host UID:GID required for mode-0600 secret bind mounts."
  type        = string
}
variable "analytics_enabled" {
  description = "Start optional analytics, authenticated Cassandra and ClickHouse; their storage remains independent."
  type        = bool
  default     = false
}
variable "tls_port" {
  description = "Loopback TLS port, separate from the project's Compose stack."
  type        = number
  default     = 28450
}
variable "network_subnet" {
  description = "Private native bank subnet, separate from Compose and existing sprint stacks."
  type        = string
  default     = "10.251.9.96/27"
}
