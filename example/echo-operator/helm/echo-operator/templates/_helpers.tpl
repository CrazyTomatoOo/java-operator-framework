{{/*
Expand the name of the chart.
*/}}
{{- define "echo-operator.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "echo-operator.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "echo-operator.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "echo-operator.labels" -}}
helm.sh/chart: {{ include "echo-operator.chart" . }}
{{ include "echo-operator.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "echo-operator.selectorLabels" -}}
app.kubernetes.io/name: {{ include "echo-operator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "echo-operator.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "echo-operator.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Validate and return the requested deployment mode.
*/}}
{{- define "echo-operator.deploymentMode" -}}
{{- $mode := .Values.deploymentMode -}}
{{- if not (has $mode (list "combined" "split")) -}}
{{- fail "deploymentMode must be \"combined\" or \"split\"" -}}
{{- end -}}
{{- $mode -}}
{{- end }}

{{/*
Resolve the namespace watched by the controller.
*/}}
{{- define "echo-operator.watchedNamespace" -}}
{{- default .Release.Namespace .Values.operator.namespace -}}
{{- end }}

{{/*
Resolve a suffix-safe split component name. Call with root and component.
*/}}
{{- define "echo-operator.componentName" -}}
{{- $root := .root -}}
{{- $component := .component -}}
{{- if not (has $component (list "controller" "webhook")) -}}
{{- fail (printf "unsupported component %q" $component) -}}
{{- end -}}
{{- printf "%s-%s" (include "echo-operator.fullname" $root | trunc 52 | trimSuffix "-") $component -}}
{{- end }}

{{- define "echo-operator.controllerName" -}}
{{- include "echo-operator.componentName" (dict "root" . "component" "controller") -}}
{{- end }}

{{- define "echo-operator.webhookName" -}}
{{- include "echo-operator.componentName" (dict "root" . "component" "webhook") -}}
{{- end }}

{{/*
Selector labels for one split component. Call with root and component.
*/}}
{{- define "echo-operator.componentSelectorLabels" -}}
{{- $root := .root -}}
{{- $component := .component -}}
{{ include "echo-operator.selectorLabels" $root }}
app.kubernetes.io/component: {{ $component }}
{{- end }}

{{/*
Resolve the single Service identity used by webhook clients.
*/}}
{{- define "echo-operator.webhookServiceName" -}}
{{- default (include "echo-operator.fullname" .) .Values.webhook.service.name -}}
{{- end }}

{{/*
Resolve a split component ServiceAccount. Call with root and component.
*/}}
{{- define "echo-operator.componentServiceAccountName" -}}
{{- $root := .root -}}
{{- $component := .component -}}
{{- $workload := index (index $root.Values $component) "workload" -}}
{{- $serviceAccount := index $workload "serviceAccount" -}}
{{- if $serviceAccount.create -}}
{{- default (include "echo-operator.componentName" (dict "root" $root "component" $component)) $serviceAccount.name -}}
{{- else -}}
{{- $serviceAccount.name -}}
{{- end -}}
{{- end }}

{{- define "echo-operator.controllerServiceAccountName" -}}
{{- include "echo-operator.componentServiceAccountName" (dict "root" . "component" "controller") -}}
{{- end }}

{{- define "echo-operator.webhookServiceAccountName" -}}
{{- include "echo-operator.componentServiceAccountName" (dict "root" . "component" "webhook") -}}
{{- end }}

{{/*
Fail early for values that would make the topology unsafe or ambiguous.
*/}}
{{- define "echo-operator.validate" -}}
{{- $mode := include "echo-operator.deploymentMode" . -}}
{{- if and (not .Values.webhook.enabled) .Values.webhook.createWebhookConfigurations -}}
{{- fail "webhook.createWebhookConfigurations requires webhook.enabled=true" -}}
{{- end -}}
{{- if and .Values.webhook.createWebhookConfigurations .Values.webhook.certAutoGenerate -}}
{{- fail "webhook.createWebhookConfigurations requires webhook.certAutoGenerate=false" -}}
{{- end -}}
{{- if and (eq $mode "split") (not .Values.webhook.enabled) -}}
{{- fail "deploymentMode=split requires webhook.enabled=true" -}}
{{- end -}}
{{- if and .Values.webhook.enabled (not .Values.webhook.certAutoGenerate) -}}
{{- if empty (trim .Values.webhook.tls.secretName) -}}
{{- fail "webhook.tls.secretName is required when webhook.certAutoGenerate=false" -}}
{{- end -}}
{{- $caBundle := trim .Values.webhook.caBundle -}}
{{- if empty $caBundle -}}
{{- fail "webhook.caBundle is required when webhook.certAutoGenerate=false" -}}
{{- end -}}
{{- if or (not (hasPrefix "-----BEGIN CERTIFICATE-----" $caBundle)) (not (hasSuffix "-----END CERTIFICATE-----" $caBundle)) -}}
{{- fail "webhook.caBundle must be a literal PEM certificate" -}}
{{- end -}}
{{- $pemBody := trimPrefix "-----BEGIN CERTIFICATE-----" $caBundle | trimSuffix "-----END CERTIFICATE-----" | trim -}}
{{- if empty $pemBody -}}
{{- fail "webhook.caBundle must be a literal PEM certificate" -}}
{{- end -}}
{{- $normalizedBody := $pemBody | replace "\n" "" | replace "\r" "" | replace " " "" -}}
{{- if not (regexMatch "^[A-Za-z0-9+/]+={0,2}$" $normalizedBody) -}}
{{- fail "webhook.caBundle must be a literal PEM certificate" -}}
{{- end -}}
{{- if ne (mod (len $normalizedBody) 4) 0 -}}
{{- fail "webhook.caBundle must be a literal PEM certificate" -}}
{{- end -}}
{{- $decoded := $normalizedBody | b64dec -}}
{{- if not (hasPrefix "0" $decoded) -}}
{{- fail "webhook.caBundle must be a literal PEM certificate" -}}
{{- end -}}
{{- end -}}
{{- $customServiceName := .Values.webhook.service.name -}}
{{- if not (empty $customServiceName) -}}
{{- if gt (len $customServiceName) 63 -}}
{{- fail "webhook.service.name must be at most 63 characters" -}}
{{- end -}}
{{- if not (regexMatch "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$" $customServiceName) -}}
{{- fail "webhook.service.name must be a valid DNS label" -}}
{{- end -}}
{{- end -}}
{{- if eq $mode "split" -}}
{{- $controllerServiceName := include "echo-operator.controllerName" . -}}
{{- $webhookServiceName := include "echo-operator.webhookServiceName" . -}}
{{- if eq $controllerServiceName $webhookServiceName -}}
{{- fail "webhook.service.name must not equal the split controller Service name" -}}
{{- end -}}
{{- range $component := list "controller" "webhook" -}}
{{- $workload := index (index $.Values $component) "workload" -}}
{{- range $label, $_ := $workload.podLabels -}}
{{- if has $label (list "app.kubernetes.io/name" "app.kubernetes.io/instance" "app.kubernetes.io/component") -}}
{{- fail (printf "%s.workload.podLabels may not override reserved selector label %q" $component $label) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- $controllerServiceAccountName := include "echo-operator.controllerServiceAccountName" . -}}
{{- $webhookServiceAccountName := include "echo-operator.webhookServiceAccountName" . -}}
{{- if and (not .Values.controller.workload.serviceAccount.create) (empty $controllerServiceAccountName) -}}
{{- fail "controller.workload.serviceAccount.name is required when create=false" -}}
{{- end -}}
{{- if and (not .Values.webhook.workload.serviceAccount.create) (empty $webhookServiceAccountName) -}}
{{- fail "webhook.workload.serviceAccount.name is required when create=false" -}}
{{- end -}}
{{- if eq $controllerServiceAccountName $webhookServiceAccountName -}}
{{- fail "controller and webhook ServiceAccount names must differ in split mode" -}}
{{- end -}}
{{- end -}}
{{- end }}
