terraform {
  required_version = ">= 1.11.0, < 1.12.0"

  required_providers {
    helm = {
      source  = "registry.terraform.io/hashicorp/helm"
      version = "3.3.0"
    }
    kubernetes = {
      source  = "registry.terraform.io/hashicorp/kubernetes"
      version = "3.3.0"
    }
  }

  backend "local" {}
}

provider "kubernetes" {
  config_path    = var.kubeconfig_path
  config_context = var.kube_context
}

provider "helm" {
  kubernetes = {
    config_path    = var.kubeconfig_path
    config_context = var.kube_context
  }
}
