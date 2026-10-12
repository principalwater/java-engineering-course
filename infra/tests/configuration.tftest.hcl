mock_provider "docker" {}

variables {
  deployment        = "sprint04"
  docker_host       = "unix:///var/run/docker.sock"
  secrets_directory = "/tmp/course-test-secrets"
  runtime_user      = "10001:10001"
}

run "boot_endpoints" {
  command = plan
  assert {
    condition     = output.deployment.backend_url == "http://127.0.0.1:29084" && output.deployment.frontend_url == "http://127.0.0.1:28084"
    error_message = "The Boot stack must retain distinct loopback endpoints."
  }
}

run "unknown_deployment" {
  command = plan
  variables {
    deployment = "unknown"
  }
  expect_failures = [var.deployment]
}

run "remote_engine" {
  command = plan
  variables {
    docker_host = "tcp://public.example:2375"
  }
  expect_failures = [var.docker_host]
}

run "invalid_secret_owner" {
  command = plan
  variables {
    runtime_user = "root"
  }
  expect_failures = [var.runtime_user]
}
