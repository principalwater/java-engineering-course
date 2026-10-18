output "deployment" {
  description = "Stable bank endpoints and owned native resource names; no credentials."
  value = {
    frontend_url  = "https://bank.valgrind-home.localhost:${var.tls_port}"
    issuer        = "https://auth-bank.valgrind-home.localhost:${var.tls_port}/realms/bank"
    database      = docker_container.platform["database"].name
    discovery     = docker_container.platform["discovery"].name
    authorization = docker_container.authorization["authorization"].name
    containers = concat([for container in values(docker_container.platform) : container.name],
      [for container in values(docker_container.authorization) : container.name],
      [for container in values(docker_container.initializer) : container.name],
      [for container in values(docker_container.application) : container.name],
    [for container in values(docker_container.edge) : container.name])
    volumes = [for volume in values(docker_volume.data) : volume.name]
  }
}
