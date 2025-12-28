import { Settings2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Switch } from "@/components/ui/switch"

interface TimeSeriesSettingsProps {
  overlay: boolean
  onOverlayChange: (value: boolean) => void
}

export function TimeSeriesSettings({
  overlay,
  onOverlayChange,
}: TimeSeriesSettingsProps) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" className="h-8 w-8">
          <Settings2 className="h-4 w-4" />
          <span className="sr-only">Chart settings</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-56" align="end">
        <div className="space-y-4">
          <h4 className="font-medium text-sm">Chart Settings</h4>
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
