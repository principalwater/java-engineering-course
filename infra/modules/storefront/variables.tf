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
  description = "Loopback port of the embedded server serving the UI."
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

variable "database_protocol" {
  description = "Native database connection protocol of the application."
  type        = string
  default     = "jdbc"
  validation {
    condition     = contains(["jdbc", "r2dbc"], var.database_protocol)
    error_message = "Select the jdbc or r2dbc protocol."
  }
}

variable "database_password_property" {
  description = "Configtree password property matching the native connection protocol."
  type        = string
  default     = "spring.datasource.password"
  validation {
    condition = var.database_password_property == (
      var.database_protocol == "r2dbc" ? "spring.r2dbc.password" : "spring.datasource.password"
    )
    error_message = "The password property must match the JDBC or R2DBC protocol."
  }
}

variable "session_cookie_property" {
  description = "Spring server property selecting an isolated servlet or reactive session cookie."
  type        = string
  default     = "server.servlet.session.cookie.name"
  validation {
    condition     = contains(["server.servlet.session.cookie.name", "server.reactive.session.cookie.name"], var.session_cookie_property)
    error_message = "Select the servlet or reactive session cookie property."
  }
}
