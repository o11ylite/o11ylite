import { Link } from "@inertiajs/react"
import { AlertCircle, Home } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Button } from "@/components/ui/button"

const messages: Record<number, { title: string; description: string }> = {
  404: {
    title: "Page not found",
    description: "The page you're looking for doesn't exist or has been moved.",
  },
  500: {
    title: "Something went wrong",
    description: "An unexpected error occurred. Please try again later.",
  },
}

const fallback = {
  title: "Something went wrong",
  description: "An unexpected error occurred. Please try again later.",
}

export default function Error({ status }: { status: number }) {
  const { title, description } = messages[status] ?? fallback

  return (
    <ApplicationLayout title={title}>
      <div className="flex min-h-[400px] flex-col items-center justify-center space-y-6">
        <div className="flex flex-col items-center space-y-2">
          <AlertCircle className="h-16 w-16 text-destructive" />
          <h1 className="text-3xl font-bold">{title}</h1>
          <p className="text-muted-foreground">{description}</p>
          {status && (
            <p className="text-sm text-muted-foreground">
              Error code: {status}
            </p>
          )}
        </div>
        <Button asChild variant="outline">
          <Link href="/explore">
            <Home className="mr-2 h-4 w-4" />
            Back to Explore
          </Link>
        </Button>
      </div>
    </ApplicationLayout>
  )
}
