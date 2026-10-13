output "deployment" {
  description = "The selected stack, its loopback endpoints and managed container names."
  value = one(concat(
    module.sprint03[*].deployment,
    module.sprint04[*].deployment,
    module.docker_exercise[*].deployment,
    module.mvc_exercise[*].deployment,
    [for stack in module.sprint05 : stack.deployment],
    [for stack in module.sprint06 : stack.deployment],
  ))
}
