import { Settings2 } from "lucide-react"

import type { TimeSeriesRenderAs } from "@/types"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"

interface TimeSeriesSettingsProps {
  overlay: boolean
  onOverlayChange: (value: boolean) => void
  renderAs: TimeSeriesRenderAs
  onRenderAsChange: (value: TimeSeriesRenderAs) => void
}

export function TimeSeriesSettings({
  overlay,
  onOverlayChange,
  renderAs,
  onRenderAsChange,
}: TimeSeriesSettingsProps) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" className="h-8 w-8">
          <Settings2 className="h-4 w-4" />
          <span className="sr-only">Chart settings</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-64" align="end">
        <div className="space-y-4">
          <h4 className="font-medium text-sm">Chart Settings</h4>
          <div className="flex items-center justify-between">
            <Label htmlFor="render-as-select" className="text-sm">
              Render as
            </Label>
            <Select
              value={renderAs}
              onValueChange={(v) => onRenderAsChange(v as TimeSeriesRenderAs)}
            >
              <SelectTrigger
                id="render-as-select"
                size="sm"
                className="w-auto min-w-[130px] text-xs"
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="line">Lines</SelectItem>
                <SelectItem value="stacked_area">Stacked area</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center justify-between">
            <Label htmlFor="overlay-switch" className="text-sm">
              Overlay charts
            </Label>
            <Switch
              id="overlay-switch"
              checked={overlay}
              onCheckedChange={onOverlayChange}
            />
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}
