# Datadog Serverless Compatibility Java Agent

Datadog library for Java to enable tracing and custom metric submission from [Azure Functions](https://azure.microsoft.com/en-us/products/functions) and [Google Cloud Run Functions (1st gen)](https://cloud.google.com/functions/1stgendocs/concepts/overview).

## Inventory version metadata

With `DD_SERVERLESS_COMPAT_METADATA_ENABLED=true`, inventory reports the wrapper package as
`serverless_compat_runtime_version`, the embedded binary release as
`serverless_compat_binary_version`, and the language engine in `runtime`.
`DD_SERVERLESS_COMPAT_PATH` selects a custom binary but does not change the wrapper package version.
The binary must supply its own embedded version; an unversioned binary must not inherit the wrapper version.

The wrapper passes a combined language and version hint (for example, `python3.13.7`)
through `DD_SERVERLESS_COMPAT_RUNTIME`
to the child binary without overwriting platform-owned version variables. This requires a
binary that supports this contract; older binaries retain their platform-based runtime detection.

# Getting Started

- [Install Serverless Monitoring for Azure Functions](https://docs.datadoghq.com/serverless/azure_functions/?code-lang=java)
- [Install Serverless Monitoring for Google Cloud Run Functions (1st gen)](https://docs.datadoghq.com/serverless/google_cloud_run/functions_1st_gen/?tab=java)
