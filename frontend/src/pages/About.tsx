import { usePage } from "@inertiajs/react"
import { Cpu, Database, Info, Server } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"

interface JavaInfo {
  version: string
  vendor: string
  home: string
  available_cpus: number
  heap_max_mb: number
  heap_used_mb: number
}

interface OsInfo {
  name: string
  version: string
  arch: string
}

export default function About() {
  const {
    o11ylite_version,
    duckdb_version,
    ducklake_version,
    sqlite_version,
    java,
    os,
    uptime_display,
    events_count_fmt,
    metrics_count_fmt,
    parquet_files,
    parquet_data_size,
  } = usePage<{
    o11ylite_version: string
    duckdb_version: string
    ducklake_version: string
    sqlite_version: string
    java: JavaInfo
    os: OsInfo
    uptime_minutes: number
    uptime_display: string
    events_count: number
    events_count_fmt: string
    metrics_count: number
    metrics_count_fmt: string
    parquet_files: number
    parquet_delete_files: number
    parquet_data_size: string
    parquet_data_bytes: number
    parquet_delete_size: string
    parquet_delete_bytes: number
  }>().props

  const breadcrumb = [{ label: "System" }, { label: "About" }]

  return (
    <ApplicationLayout title={breadcrumb}>
      <div className="space-y-6">
        {/* Version Information */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Info size={18} />
              Versions
            </CardTitle>
            <CardDescription>
              Installed component versions and builds.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-4">
              <VersionItem label="O11yLite" value={o11ylite_version} />
              <VersionItem label="DuckDB" value={duckdb_version} />
              <VersionItem label="DuckLake" value={ducklake_version} />
              <VersionItem label="SQLite" value={sqlite_version} />
            </div>
          </CardContent>
        </Card>

        {/* Java Runtime */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Server size={18} />
              Java Runtime
            </CardTitle>
            <CardDescription>
              JVM version, vendor, and memory usage.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Version</dt>
                <dd className="text-lg font-semibold">{java.version}</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Vendor</dt>
                <dd className="text-lg font-semibold">{java.vendor}</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Home</dt>
                <dd className="text-sm font-mono mt-1 break-all">{java.home}</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Heap Memory</dt>
                <dd className="text-lg font-semibold">
                  {java.heap_used_mb} / {java.heap_max_mb} MB
                </dd>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* System */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Cpu size={18} />
              System
            </CardTitle>
            <CardDescription>
              Operating system and hardware.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <dt className="text-sm font-medium text-muted-foreground">OS</dt>
                <dd className="text-lg font-semibold">{os.name} {os.version}</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Architecture</dt>
                <dd className="text-lg font-semibold">{os.arch}</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Available CPUs</dt>
                <dd className="text-lg font-semibold">{java.available_cpus}</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Uptime</dt>
                <dd className="text-lg font-semibold">{uptime_display}</dd>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Data Stores */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Database size={18} />
              Data Stores
            </CardTitle>
            <CardDescription>
              Telemetry volume and storage.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Events</dt>
                <dd className="text-lg font-semibold">{events_count_fmt}</dd>
                <dd className="text-sm text-muted-foreground mt-0.5">Total rows</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Metrics</dt>
                <dd className="text-lg font-semibold">{metrics_count_fmt}</dd>
                <dd className="text-sm text-muted-foreground mt-0.5">Total datapoints</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Parquet Files</dt>
                <dd className="text-lg font-semibold">{parquet_files.toLocaleString()}</dd>
                <dd className="text-sm text-muted-foreground mt-0.5">Data files</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-muted-foreground">Parquet Size</dt>
                <dd className="text-lg font-semibold">{parquet_data_size}</dd>
                <dd className="text-sm text-muted-foreground mt-0.5">Total on disk</dd>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </ApplicationLayout>
  )
}

function VersionItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-sm font-medium text-muted-foreground">{label}</dt>
      <dd className="text-lg font-semibold">{value}</dd>
    </div>
  )
}
