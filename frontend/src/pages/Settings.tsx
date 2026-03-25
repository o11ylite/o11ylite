import { useState } from "react"
import { router, usePage } from "@inertiajs/react"
import { RotateCcw, Save } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

interface CoreSetting {
  key: string
  env_var: string
  default: string | null
  value: string
  description: string
  masked: boolean
}

interface AppSetting {
  key: string
  env_var: string
  default: number | string | null
  value: number | string | null
  source: "kv" | "env" | "default"
  description: string
}

const sourceStyles = {
  kv:      "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
  env:     "bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200",
  default: "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400",
} as const

function AppSettingRow({
  setting,
  runtimeEnabled,
}: {
  setting: AppSetting
  runtimeEnabled: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(String(setting.value ?? ""))

  const canEdit = runtimeEnabled
  const isOverridden = setting.source === "kv"
  const displayValue = setting.value ?? "--"

  function handleSave() {
    const parsed = Number(draft)
    const value = Number.isNaN(parsed) ? draft : parsed
    router.post(
      "/system/settings",
      { key: setting.key, value },
      { preserveScroll: true, onSuccess: () => setEditing(false) }
    )
  }

  function handleReset() {
    router.delete(`/system/settings/${setting.key}`, {
      preserveScroll: true,
    })
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Enter") handleSave()
    if (e.key === "Escape") {
      setEditing(false)
      setDraft(String(setting.value ?? ""))
    }
  }

  return (
    <TableRow>
      <TableCell>
        <div className="font-medium">{setting.key}</div>
        <p className="text-xs text-muted-foreground mt-0.5">
          {setting.description}
        </p>
        <code className="text-xs text-muted-foreground">{setting.env_var}</code>
      </TableCell>
      <TableCell className="text-sm text-muted-foreground">
        {String(setting.default ?? "--")}
      </TableCell>
      <TableCell>
        {editing ? (
          <div className="flex items-center gap-1">
            <Input
              className="h-8 w-40"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={handleKeyDown}
              autoFocus
            />
            <Button variant="ghost" size="icon" className="h-8 w-8" onClick={handleSave} title="Save">
              <Save size={14} />
            </Button>
          </div>
        ) : (
          <div className="flex items-center gap-2">
            <span
              className={canEdit ? "cursor-pointer hover:underline" : ""}
              onClick={() => canEdit && setEditing(true)}
              title={canEdit ? "Click to edit" : undefined}
            >
              {String(displayValue)}
            </span>
          </div>
        )}
      </TableCell>
      <TableCell>
        <Badge variant="outline" className={sourceStyles[setting.source]}>
          {setting.source}
        </Badge>
      </TableCell>
      <TableCell>
        {canEdit && isOverridden && (
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7"
            title="Reset to default"
            onClick={handleReset}
          >
            <RotateCcw size={14} />
          </Button>
        )}
      </TableCell>
    </TableRow>
  )
}

export default function Settings() {
  const { version, core_settings, app_settings, runtime_app_config, flash } =
    usePage<{
      version: string
      core_settings: CoreSetting[]
      app_settings: AppSetting[]
      runtime_app_config: boolean
      flash: { message?: string; error?: string }
    }>().props

  const breadcrumb = [{ label: "System" }, { label: "Settings" }]

  return (
    <ApplicationLayout title={breadcrumb}>
      <div className="space-y-6">
        <p className="text-sm text-muted-foreground">
          System configuration and runtime settings. Version: <strong>{version}</strong>
        </p>

        {flash?.message && (
          <div className="rounded-md bg-muted px-4 py-3 text-sm">
            {flash.message}
          </div>
        )}
        {flash?.error && (
          <div className="rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
            {flash.error}
          </div>
        )}

        {/* Core Configuration (read-only) */}
        <Card>
          <CardHeader>
            <CardTitle>Core Configuration</CardTitle>
            <CardDescription>
              Static settings loaded from environment variables at startup. These cannot be changed at runtime.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Setting</TableHead>
                  <TableHead>Default</TableHead>
                  <TableHead>Value</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {core_settings.map((setting) => (
                  <TableRow key={setting.key}>
                    <TableCell>
                      <div className="font-medium">{setting.key}</div>
                      <p className="text-xs text-muted-foreground mt-0.5">
                        {setting.description}
                      </p>
                      <code className="text-xs text-muted-foreground">
                        {setting.env_var}
                      </code>
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {setting.masked ? "--" : (setting.default ?? "--")}
                    </TableCell>
                    <TableCell className="text-sm">
                      {setting.masked ? (
                        <span className="text-muted-foreground">{setting.value}</span>
                      ) : (
                        setting.value || "--"
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

        {/* App Configuration (runtime-mutable) */}
        <Card>
          <CardHeader>
            <CardTitle>Application Configuration</CardTitle>
            <CardDescription>
              Runtime settings resolved with precedence: KV store &rarr; environment variable &rarr; default.
              {!runtime_app_config && (
                <span className="ml-1 font-medium text-amber-600 dark:text-amber-400">
                  Runtime overrides are disabled. Set O11YLITE_ENABLE_RUNTIME_APP_CONFIG=true to enable.
                </span>
              )}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Setting</TableHead>
                  <TableHead>Default</TableHead>
                  <TableHead>Value</TableHead>
                  <TableHead>Source</TableHead>
                  <TableHead className="w-10" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {app_settings.map((setting) => (
                  <AppSettingRow
                    key={setting.key}
                    setting={setting}
                    runtimeEnabled={runtime_app_config}
                  />
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      </div>
    </ApplicationLayout>
  )
}
