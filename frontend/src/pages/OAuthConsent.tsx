import { useState } from "react"
import { router } from "@inertiajs/react"
import { AlertTriangle, KeyRound } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"

type OAuthClient = {
  name: string
  id: string
  host: string
  uri: string | null
}

type OAuthConsentProps = {
  client: OAuthClient
  redirect_uri: string
  redirect_host: string
  localhost_redirect: boolean
  scope: string
  resource: string | null
  authorization_params: Record<string, string>
}

const SCOPE_DESCRIPTIONS: Record<string, string> = {
  read: "Read traces, logs, metrics, alert rules, and notebooks.",
  ingest: "Send telemetry.",
  write: "Read all data, and create or change alert rules and notebooks.",
  admin: "Full access, including API key management.",
}

export default function OAuthConsent({
  client,
  redirect_host,
  localhost_redirect,
  scope,
  authorization_params,
}: OAuthConsentProps) {
  const [submitting, setSubmitting] = useState(false)

  const decide = (decision: "approve" | "deny") => {
    // The server re-validates every parameter; it redirects the browser
    // to the client's redirect URI with a code or an error.
    router.post(
      "/oauth/authorize",
      { ...authorization_params, decision },
      {
        onBefore: () => setSubmitting(true),
        onFinish: () => setSubmitting(false),
      }
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/40 p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-xl">
            <KeyRound className="h-5 w-5" />
            Authorize {client.name}
          </CardTitle>
          <CardDescription>
            This application is asking to access O11yLite on your behalf.
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-4 text-sm">
          <dl className="space-y-3">
            <div>
              <dt className="text-muted-foreground">Application identity</dt>
              <dd className="font-medium break-all">{client.host}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Access requested</dt>
              <dd className="font-medium">{scope}</dd>
              <dd>{SCOPE_DESCRIPTIONS[scope] ?? scope}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">You will be sent to</dt>
              <dd className="font-medium break-all">{redirect_host}</dd>
            </div>
          </dl>

          {localhost_redirect && (
            <div
              role="alert"
              className="flex gap-2 rounded-md border border-amber-500/50 bg-amber-500/10 p-3"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
              <p>
                Access will be granted to a program running on this computer.
                Only approve if you just started this sign-in from{" "}
                <span className="font-medium">{client.name}</span>.
              </p>
            </div>
          )}

          <p className="text-muted-foreground">
            Only approve applications you trust. Access lasts until you stop
            using the application for 30 days.
          </p>
        </CardContent>

        <CardFooter className="flex justify-end gap-2">
          <Button
            variant="outline"
            disabled={submitting}
            onClick={() => decide("deny")}
          >
            Deny
          </Button>
          <Button disabled={submitting} onClick={() => decide("approve")}>
            Approve
          </Button>
        </CardFooter>
      </Card>
    </div>
  )
}
