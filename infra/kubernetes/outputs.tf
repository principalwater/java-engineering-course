output "namespace" {
  description = "Owned namespace; no credential material."
  value       = local.namespace
}

output "kafka_bootstrap" {
  description = "Internal TLS/SCRAM Kafka bootstrap service."
  value       = "bank-kafka-kafka-bootstrap.${local.namespace}.svc.cluster.local:9093"
}

output "public_values_sha256" {
  description = "Digest of prepared public configuration for reviewed-plan provenance."
  value       = filesha256(var.public_values_path)
}
