# Installation

O11yLite ships as a single container image. Choose the method that fits your infrastructure.

## Docker (single host)

The simplest way to run O11yLite. One container, one volume.

```bash
docker run -d \
  --name o11ylite \
  -p 80:80 \
  -v o11ylite-data:/data \
  ghcr.io/o11ylite/o11ylite:latest
```

The UI is at `http://localhost` and OTLP ingestion (both gRPC and HTTP) is available on the same port. Configuration is done through environment variables. See [Authentication](authentication.md) for auth-related options.

## Helm chart (Kubernetes)

For Kubernetes deployments, O11yLite provides a Helm chart that deploys a single-replica StatefulSet.

### Install

```bash
helm install o11ylite oci://ghcr.io/o11ylite/charts/o11ylite
```

By default this runs without persistent storage, suitable for trying things out. For production use, enable persistence so data survives pod restarts:

```bash
helm install o11ylite oci://ghcr.io/o11ylite/charts/o11ylite \
  --set persistence.enabled=true
```

### Values

| Key | Default | Description |
|-----|---------|-------------|
| `image.repository` | `ghcr.io/o11ylite/o11ylite` | Container image repository |
| `image.tag` | Chart `appVersion` | Image tag override |
| `image.pullPolicy` | `IfNotPresent` | Image pull policy |
| `service.type` | `ClusterIP` | Kubernetes Service type |
| `service.port` | `80` | Service port |
| `persistence.enabled` | `false` | Enable persistent volume for `/data` |
| `persistence.size` | `10Gi` | Persistent volume size |
| `persistence.storageClass` | (cluster default) | Storage class name |
| `resources` | `{}` | Container resource requests/limits |
| `env` | `[]` | Extra environment variables |
| `existingSecret` | `""` | Name of an existing Secret to inject as env vars |

### Persistence

When `persistence.enabled=true`, the chart creates a `volumeClaimTemplate` that provisions a `ReadWriteOnce` PVC mounted at `/data`. Without it, data is stored in the container's ephemeral filesystem and lost on pod restart.

### Environment variables

Pass configuration through the `env` value:

```bash
helm install o11ylite oci://ghcr.io/o11ylite/charts/o11ylite \
  --set persistence.enabled=true \
  --set env[0].name=O11YLITE_OIDC_ISSUER_URL \
  --set env[0].value=https://auth.example.com
```

Or with a values file:

```yaml
persistence:
  enabled: true
  size: 20Gi

env:
  - name: O11YLITE_OIDC_ISSUER_URL
    value: https://auth.example.com
  - name: O11YLITE_OIDC_CLIENT_ID
    value: o11ylite
```

For sensitive values like `O11YLITE_OIDC_CLIENT_SECRET`, use `existingSecret` instead of putting them in plain text. Create a Secret whose keys match the environment variable names the app expects, and all keys will be injected as env vars:

```bash
kubectl create secret generic o11ylite-secrets \
  --from-literal=O11YLITE_OIDC_CLIENT_SECRET=secret

helm install o11ylite oci://ghcr.io/o11ylite/charts/o11ylite \
  --set existingSecret=o11ylite-secrets
```

For selective key mapping (e.g., when the Secret key doesn't match the env var name), use `env` with `valueFrom` instead:

```yaml
env:
  - name: O11YLITE_OIDC_CLIENT_SECRET
    valueFrom:
      secretKeyRef:
        name: my-secret
        key: oidc-secret
```

### Exposing the service

The chart creates a `ClusterIP` Service by default. To expose O11yLite externally, use an Ingress, an API gateway (e.g., Kong, Ambassador, AWS API Gateway), a `LoadBalancer` service type, or port-forward for quick access:

```bash
kubectl port-forward svc/o11ylite 8080:80
```

### Collecting telemetry

To collect logs, metrics, and traces from your cluster workloads into O11yLite, see the [Kubernetes OpenTelemetry Setup](kubernetes-otel-setup.md) guide.
