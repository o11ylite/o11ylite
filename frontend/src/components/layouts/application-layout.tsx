import { type ReactNode, useState } from "react"
import { PanelRightIcon } from "lucide-react"

import { AppSidebar } from "@/components/app-sidebar"
import { TimeRangeSelector } from "@/components/time-range-selector"
import { Button } from "@/components/ui/button"
import { CollapsiblePanel } from "@/components/collapsible-panel"
import { Separator } from "@/components/ui/separator"
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from "@/components/ui/sidebar"
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbList,
  BreadcrumbPage,
} from "@/components/ui/breadcrumb"

export default function ApplicationLayout({
  children,
  title,
  showTimeRange = false,
  rightPanel,
}: {
  children: ReactNode
  title?: string
  showTimeRange?: boolean
  rightPanel?: ReactNode
}) {
  const [rightPanelOpen, setRightPanelOpen] = useState(true)

  const rightPanelTrigger = rightPanel && (
    <>
      <Separator orientation="vertical" className="h-4" />
      <Button
        variant="ghost"
        size="icon"
        onClick={() => setRightPanelOpen((open) => !open)}
      >
        <PanelRightIcon />
        <span className="sr-only">Toggle Right Panel</span>
      </Button>
    </>
  )

  return (
    <SidebarProvider>
      <AppSidebar />
      <SidebarInset>
        <header className="flex h-16 shrink-0 items-center gap-2 border-b px-4">
          <SidebarTrigger className="-ml-1" />
          <Separator orientation="vertical" className="mr-2 h-4" />
          <Breadcrumb>
            <BreadcrumbList>
              <BreadcrumbItem>
                <BreadcrumbPage>{title ?? "Home"}</BreadcrumbPage>
              </BreadcrumbItem>
            </BreadcrumbList>
          </Breadcrumb>
          <div className="ml-auto flex h-full items-center gap-2">
            {showTimeRange && <TimeRangeSelector />}
            {rightPanelTrigger}
          </div>
        </header>
        <div className="flex flex-1 overflow-hidden">
          <main className="flex flex-1 flex-col gap-4 overflow-auto p-4">
            {children}
          </main>
          {rightPanel && (
            <CollapsiblePanel
              open={rightPanelOpen}
              width="20rem"
              className="border-l bg-background"
            >
              {rightPanel}
            </CollapsiblePanel>
          )}
        </div>
      </SidebarInset>
    </SidebarProvider>
  )
}
