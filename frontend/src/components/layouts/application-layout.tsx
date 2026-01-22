import { type ReactNode } from "react"
import { PanelRightIcon } from "lucide-react"

import { useLocalStorage } from "@/hooks/use-local-storage"
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
  const [rightPanelOpen, setRightPanelOpen] = useLocalStorage(
    "right_panel_open",
    true
  )

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
      <SidebarInset className="min-w-0">
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
        <div className="flex flex-1 items-start">
          <main className="flex min-h-[600px] min-w-0 flex-1 flex-col gap-4 p-4 [&>*]:flex-1">
            {children}
          </main>
          {rightPanel && (
            <CollapsiblePanel
              open={rightPanelOpen}
              width="20rem"
              className="sticky top-0 max-h-[calc(100vh)] overflow-y-auto border-l bg-background"
            >
              {rightPanel}
            </CollapsiblePanel>
          )}
        </div>
      </SidebarInset>
    </SidebarProvider>
  )
}
