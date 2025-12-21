import { Sparkles } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { type Field } from "@/types"
import { QueryBuilder } from "@/components/query-builder"
import { FieldsPanel } from "@/components/fields-panel"

// Mock data - will come from backend later
const MOCK_FIELDS: Field[] = [
  { name: "timestamp", type: "time" },
  { name: "service", type: "str" },
  { name: "severity", type: "enum" },
  { name: "message", type: "str" },
  { name: "trace_id", type: "str" },
  { name: "span_id", type: "str" },
  { name: "duration_ms", type: "num" },
  { name: "status_code", type: "num" },
  { name: "http_method", type: "str" },
  { name: "http_path", type: "str" },
]

function ResultsPlaceholder() {
  return (
    <div className="flex-1 rounded-lg bg-muted/30 flex items-center justify-center">
      <div className="text-center">
        <div className="w-10 h-10 mx-auto mb-3 rounded-full bg-muted flex items-center justify-center">
          <Sparkles size={18} className="text-muted-foreground" />
        </div>
        <p className="text-xs text-muted-foreground">
          Run a query to explore your data
        </p>
      </div>
    </div>
  )
}

export default function Explore() {
  const handleFieldClick = (fieldName: string) => {
    // Will add filter with this field - to be implemented
    console.log("Add filter for field:", fieldName)
  }

  const rightPanel = (
    <FieldsPanel fields={MOCK_FIELDS} onFieldClick={handleFieldClick} />
  )

  return (
    <ApplicationLayout title="Explore" showTimeRange rightPanel={rightPanel}>
      <div className="flex flex-col h-full gap-3">
        <QueryBuilder fields={MOCK_FIELDS} />
        <ResultsPlaceholder />
      </div>
    </ApplicationLayout>
  )
}
