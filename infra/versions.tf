terraform {
  required_version = ">= 1.11.0, < 1.12.0"

  required_providers {
    docker = {
      source  = "registry.terraform.io/kreuzwerker/docker"
      version = "4.6.0"
    }
  }

  backend "local" {}
}

provider "docker" {
  host = var.docker_host
}
