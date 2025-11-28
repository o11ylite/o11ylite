# O11yLite

O11yLite is a lightweight observability engine that speaks OpenTelemetry.

It's designed to work as a single process.

The analytics engine is powered by DuckDB and SQLite.

## Components

### Backend (`backend/`)

A Clojure service, the core of O11yLite.

* Handles telemetry ingestion, operates SQLite and DuckDB, manages Parquet files
* Functions as Inertia.js backend, serving HTML with embedded page data
* Integrant component system for lifecycle management

Key directories:
```
backend/
├── src/o11ylite/
│   ├── system.clj           # Integrant system config & lifecycle
│   ├── components/          # Integrant components
│   │   ├── server.clj       #   Jetty HTTP server
│   │   ├── router.clj       #   Reitit routes (assembles all routes)
│   │   └── inertia.clj      #   Inertia.js config
│   ├── routes/              # Route handlers
│   │   ├── home.clj         #   Page routes (Inertia)
│   │   └── health.clj       #   Health check endpoints
│   ├── inertia/             # Inertia.js adapter
│   │   ├── core.clj         #   Request handling, JSON encoding
│   │   ├── template.clj     #   HTML template, Vite manifest
│   │   └── middleware.clj   #   Ring middleware
│   └── util/
│       └── response.clj     # Response helpers (json, inertia)
└── resources/
    └── system.edn           # System configuration (Aero)
```

### Frontend (`frontend/`)

An Inertia.js React frontend built with Vite + TypeScript.

```
frontend/
├── src/
│   ├── main.tsx             # Inertia app entry point
│   └── pages/               # Page components (matched by name)
│       └── Home.tsx
├── public/
│   └── favicon.svg          # Static assets
└── vite.config.ts           # Vite config (base: /frontend)
```

Route handlers return Inertia responses that reference page components by name:
```clojure
;; backend - returns {:component "Home" :props {...}}
(response/inertia "Home" {:greeting "Welcome"})
```
```tsx
// frontend/src/pages/Home.tsx - receives props
export default function Home({ greeting }: { greeting: string }) {
  return <h1>{greeting}</h1>
}
```

## Distributions

We bundle everything to a single docker image, using s6 overlay.

## Development

### Dev Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         Caddy                               │
│                  (reverse proxy, TLS)                       │
├─────────────────────────────────────────────────────────────┤
│  /frontend/*  │  /*  (everything else)                      │
│       ↓       │           ↓                                 │
│    ┌──────┐   │   ┌────────────┐                            │
│    │ Vite │   │   │  Clojure   │                            │
│    │ :5173│   │   │  Backend   │                            │
│    └──────┘   │   │   :3000    │                            │
│               │   └────────────┘                            │
└─────────────────────────────────────────────────────────────┘
```

- **Caddy** - Reverse proxy at `https://o11ylite.localhost`, routes `/frontend/*` to Vite
- **Vite** - Dev server with HMR for frontend assets
- **Backend** - Clojure/Ring/Reitit server, serves Inertia.js HTML responses

### Quick Start

First time running you need to install this first: [Practicalli Clojure CLI Config](https://practical.li/clojure/clojure-cli/practicalli-config/).

Start all services locally:
```bash
dev/start all
```
Visit `https://o11ylite.localhost`

### REPL-Driven Development

For backend development with REPL:
```bash
dev/start          # Starts Caddy, Vite, and nREPL server
```

Then connect your editor to the REPL and:
```clojure
;; In backend/dev/user.clj
(go)               ;; Start the system
(reset)            ;; Restart after code changes
(halt)             ;; Stop the system
```

### Running Services Individually

```bash
# Frontend (Vite dev server)
cd frontend && npm run dev

# Backend
cd backend && clojure -M:run/dev

# Caddy (uses dev/Caddyfile)
caddy run --config dev/Caddyfile
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `O11YLITE_DEV` | - | Set to enable dev mode (uses Vite HMR) |
| `PORT` | 3000 | Backend server port |
| `HOST` | 0.0.0.0 | Backend server host |
| `ASSET_BASE_URL` | /frontend | Base URL for frontend assets |


### Running Testings

Please read `backend/README.md` and `frontend/README.md`

### Building for Production

```bash
# Build frontend assets
cd frontend && npm run build

# Copy manifest to backend resources for prod deployment
cp -r frontend/dist/.vite backend/resources/
```

