{{- /* Одно представление env для ConfigMap и его контрольной суммы Pod: правка мониторинга не перезапускает банк. */ -}}
{{- define "bank.applicationEnv" -}}
{{- $root := .root -}}
{{- $name := .name -}}
{{- $app := .app -}}
{{- $web := .web -}}
SPRING_APPLICATION_NAME: {{ $name | quote }}
SPRING_CLOUD_ZOOKEEPER_CONNECT_STRING: discovery:2181
SPRING_CLOUD_ZOOKEEPER_CONFIG_WATCH_ENABLED: "false"
SPRING_CLOUD_ZOOKEEPER_DISCOVERY_INSTANCE_HOST: {{ $name | quote }}
SPRING_CLOUD_ZOOKEEPER_DISCOVERY_INSTANCE_PORT: "8443"
SPRING_CLOUD_ZOOKEEPER_DISCOVERY_INSTANCE_SSL_PORT: "8443"
BANK_CONFIG_IMPORT: {{ printf "zookeeper:,vault:///kv/%s/%s" $root.Release.Namespace $name | quote }}
BANK_VAULT_ENABLED: "true"
BANK_VAULT_ROLE: {{ printf "%s-%s" $root.Release.Namespace $name | quote }}
MANAGEMENT_ZIPKIN_TRACING_EXPORT_ENABLED: "true"
MANAGEMENT_TRACING_SAMPLING_PROBABILITY: {{ ternary "0.1" "1.0" (eq $root.Values.global.environment "prod") | quote }}
LOGGING_LEVEL_DEV_PRINCIPALWATER_BANK: {{ ternary "INFO" "DEBUG" (eq $root.Values.global.environment "prod") | quote }}
BANK_LOGGING_LOGSTASH_TRANSPORT: tls
BANK_LOGGING_LOGSTASH_TRUST_STORE: file:/tmp/bank-trust.jks
BANK_LOGGING_LOGSTASH_TRUST_STORE_TYPE: JKS
BANK_LOGGING_LOGSTASH_TRUST_STORE_PASSWORD: changeit
BANK_SECURITY_ISSUER: {{ include "bank.issuer" $root | quote }}
BANK_SECURITY_JWK_SET_URI: https://authorization:8443/realms/bank/protocol/openid-connect/certs
{{- if $app.audience }}
BANK_SECURITY_AUDIENCE: {{ $app.audience | quote }}
{{- end }}
{{- if $app.jdbc }}
SPRING_DATASOURCE_URL: {{ printf "jdbc:postgresql://database:5432/bank?currentSchema=%s&connectTimeout=5&socketTimeout=15" $name | quote }}
SPRING_DATASOURCE_USERNAME: {{ $name | quote }}
{{- end }}
{{- if not $web }}
SPRING_MAIN_WEB_APPLICATION_TYPE: none
SPRING_CLOUD_ZOOKEEPER_DISCOVERY_ENABLED: "false"
BANK_PROBES_ENABLED: "true"
BANK_OBSERVABILITY_METRICS_SERVER_ENABLED: "true"
BANK_OBSERVABILITY_METRICS_SERVER_PORT: "9400"
BANK_OBSERVABILITY_METRICS_SERVER_HOST: "::"
{{- else }}
SERVER_PORT: "8443"
SERVER_ADDRESS: "::"
SERVER_SSL_ENABLED: "true"
SERVER_SSL_BUNDLE: bank
MANAGEMENT_SERVER_PORT: "8080"
MANAGEMENT_SERVER_ADDRESS: "::"
MANAGEMENT_SERVER_SSL_ENABLED: "false"
{{- end }}
{{- if $app.kafka }}
SPRING_KAFKA_BOOTSTRAP_SERVERS: {{ printf "%s-kafka-bootstrap:9093" $root.Values.global.kafkaCluster | quote }}
BANK_KAFKA_BOOTSTRAP_SERVERS: {{ printf "%s-kafka-bootstrap:9093" $root.Values.global.kafkaCluster | quote }}
BANK_KAFKA_USER: {{ printf "%s-service" $name | quote }}
SPRING_KAFKA_SECURITY_PROTOCOL: SASL_SSL
SPRING_KAFKA_PROPERTIES_SASL_MECHANISM: SCRAM-SHA-512
SPRING_KAFKA_SSL_TRUSTSTORETYPE: PEM
SPRING_KAFKA_SSL_TRUSTSTORELOCATION: file:/run/kafka/ca.crt
SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM: https
{{- end }}
{{- if $app.oauthScope }}
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_SERVICE_TOKEN_URI: https://authorization:8443/realms/bank/protocol/openid-connect/token
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_SERVICE_PROVIDER: service
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_SERVICE_AUTHORIZATION_GRANT_TYPE: client_credentials
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_SERVICE_CLIENT_ID: {{ printf "%s-service" $name | quote }}
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_SERVICE_SCOPE: {{ $app.oauthScope | quote }}
{{- end }}
{{- if eq $name "gateway" }}
SPRING_THREADS_VIRTUAL_ENABLED: "false"
{{- end }}
{{- if has $name (list "accounts" "front" "notifications") }}
BANK_ANALYTICS_ENABLED: {{ $root.Values.global.analyticsEnabled | quote }}
{{- end }}
{{- if eq $name "front" }}
SERVER_FORWARD_HEADERS_STRATEGY: framework
SERVER_SERVLET_SESSION_COOKIE_SECURE: "true"
SERVER_SERVLET_SESSION_COOKIE_NAME: __Host-bank-session
# Собственный ClientRegistration читает bank.login.*, а не provider.bank из Boot auto-configuration.
BANK_TOKEN_URI: https://authorization:8443/realms/bank/protocol/openid-connect/token
BANK_USER_INFO_URI: https://authorization:8443/realms/bank/protocol/openid-connect/userinfo
{{- end }}
{{- if eq $name "analytics" }}
BANK_CASSANDRA_HOST: cassandra
BANK_CLICKHOUSE_URL: http://clickhouse:8123
{{- end }}
{{- end -}}
