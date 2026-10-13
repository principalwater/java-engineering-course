variable "project_directory" {
  description = "Existing Gradle storefront source directory."
  type        = string
}
variable "stack_name" {
  description = "Unique resource prefix for the selected storefront variant."
  type        = string
}
variable "profile" {
  description = "Actual database implementation: PostgreSQL or in-memory H2."
  type        = string
  validation {
    condition     = contains(["postgresql", "h2"], var.profile)
    error_message = "Select the postgresql or h2 profile."
  }
}
variable "http_port" {
  description = "Loopback port of the embedded Tomcat serving the UI."
  type        = number
}
variable "analytics_port" {
  description = "Loopback ClickHouse HTTP port."
  type        = number
}
variable "analytics_enabled" {
  description = "Enable optional order analytics without adding a purchase dependency."
  type        = bool
}
variable "secrets_directory" {
  description = "External secret-file directory; secret contents never enter state."
  type        = string
}
variable "runtime_user" {
  description = "Host UID:GID for reading mode-0600 secret mounts."
  type        = string
}
