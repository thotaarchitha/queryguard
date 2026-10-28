{{- define "qg.labels" -}}
app.kubernetes.io/part-of: queryguard
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "qg.secretName" -}}
{{- default (printf "%s-secrets" .Release.Name) .Values.secrets.existingSecret -}}
{{- end -}}

{{- define "qg.pgEnv" -}}
- name: QG_PG_HOST
  value: {{ .Values.postgres.host | quote }}
- name: QG_PG_PORT
  value: {{ .Values.postgres.port | quote }}
- name: QG_EXPLAIN_USER
  value: qg_explain
- name: QG_EXPLAIN_PASSWORD
  valueFrom: { secretKeyRef: { name: {{ include "qg.secretName" . }}, key: explain-password } }
- name: QG_CONFIG_DB_URL
  value: {{ printf "jdbc:postgresql://%s:%v/%s" .Values.postgres.host .Values.postgres.port .Values.postgres.configDatabase | quote }}
- name: QG_CONFIG_DB_USER
  value: qg_config
- name: QG_CONFIG_DB_PASSWORD
  valueFrom: { secretKeyRef: { name: {{ include "qg.secretName" . }}, key: config-password } }
- name: QG_RULES_FILE
  value: /etc/queryguard/rules.yml
{{- end -}}
