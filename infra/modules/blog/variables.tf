variable "project_directory" {
  description = "Absolute existing blog project path."
  type        = string
}
variable "stack_name" {
  description = "Unique prefix for this independently managed stack."
  type        = string
}
variable "spring_boot" {
  description = "Use the Boot JAR contract; false retains the sprint 03 WAR contract."
  type        = bool
}
variable "frontend_port" {
  description = "Loopback frontend port."
  type        = number
}
variable "backend_port" {
  description = "Loopback backend port for diagnostics."
  type        = number
}
variable "analytics_port" {
  description = "Loopback ClickHouse HTTP port."
  type        = number
}
variable "analytics_enabled" {
  description = "Deploy the existing read-only PostgreSQL/ClickHouse integration."
  type        = bool
}
variable "secrets_directory" {
  description = "External directory containing the three blog secret files."
  type        = string
}
variable "runtime_user" {
  description = "Host UID:GID for the non-root Boot process and secret permissions."
  type        = string
}
