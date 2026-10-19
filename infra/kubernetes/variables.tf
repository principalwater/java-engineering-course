variable "environment" {
  description = "Isolated bank environment and namespace suffix."
  type        = string
  default     = "dev"
  nullable    = false
  validation {
    condition     = contains(["dev", "test", "prod"], var.environment)
    error_message = "Environment must be dev, test or prod."
  }
}

variable "profile" {
  description = "Independent Kafka installation profile; changing it requires another cluster/state."
  type        = string
  default     = "single"
  nullable    = false
  validation {
    condition     = contains(["single", "ha"], var.profile)
    error_message = "Profile must be single or ha."
  }
}

variable "kubeconfig_path" {
  description = "Absolute external AES-backed kubeconfig path; contents never enter state."
  type        = string
  nullable    = false
  validation {
    condition     = startswith(var.kubeconfig_path, "/")
    error_message = "An explicit absolute kubeconfig path is required."
  }
}

variable "kube_context" {
  description = "Explicit dedicated kind context; default/foreign contexts are rejected."
  type        = string
  nullable    = false
  validation {
    condition     = var.kube_context == "kind-bank10-${var.environment}-${var.profile}"
    error_message = "Context must identify this bank environment and profile."
  }
}

variable "project_dir" {
  description = "Absolute source project directory containing the owned bank chart."
  type        = string
  nullable    = false
  validation {
    condition     = startswith(var.project_dir, "/") && fileexists("${var.project_dir}/charts/bank/Chart.yaml")
    error_message = "Project directory must contain charts/bank/Chart.yaml."
  }
}

variable "public_values_path" {
  description = "External prepared public values file; no Secret data or private keys."
  type        = string
  nullable    = false
  validation {
    condition     = startswith(var.public_values_path, "/") && fileexists(var.public_values_path) && !startswith(abspath(var.public_values_path), "${abspath(var.project_dir)}/")
    error_message = "Prepared public values must exist outside the source tree."
  }
}

variable "analytics_enabled" {
  description = "Enable the separate Cassandra/ClickHouse analytics scenario."
  type        = bool
  default     = false
  nullable    = false
}

variable "superset_enabled" {
  description = "Enable Superset only with analytics and separately provisioned external Secret."
  type        = bool
  default     = false
  nullable    = false
  validation {
    condition     = !var.superset_enabled || var.analytics_enabled
    error_message = "Superset requires analytics_enabled=true."
  }
}
