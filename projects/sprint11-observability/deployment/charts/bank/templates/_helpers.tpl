{{- define "bank.labels" -}}
app.kubernetes.io/part-of: bank11
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
bank11.io/environment: {{ .Values.global.environment }}
{{- end -}}
{{- define "bank.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: [ALL]
seccompProfile:
  type: RuntimeDefault
{{- end -}}
{{- define "bank.podSecurity" -}}
runAsUser: 10001
runAsGroup: 10001
runAsNonRoot: true
fsGroup: 10001
fsGroupChangePolicy: OnRootMismatch
seccompProfile:
  type: RuntimeDefault
{{- end -}}
{{- define "bank.issuer" -}}
https://auth-bank.valgrind-home.localhost:{{ .Values.global.externalPort }}/realms/bank
{{- end -}}
