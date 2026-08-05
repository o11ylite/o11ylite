import * as React from "react"
import { Link, usePage } from "@inertiajs/react"
import {
  Search,
  BookText,
  ShieldAlert,
  ChevronRight,
  Clock,
  Database,
  Info,
  KeyRound,
  Settings,
  type LucideIcon,
} from "lucide-react"
import logo from "@/assets/logo.svg"

import { NavUser } from "@/components/nav-user"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  SidebarRail,
} from "@/components/ui/sidebar"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { useLocalStorage } from "@/hooks/use-local-storage"
import type { AuthSharedData } from "@/types"

type NavItem = {
  title: string
  url: string
  icon: LucideIcon
}

type NavGroup = {
  label?: string
  icon?: LucideIcon
  items: NavItem[]
}

const navigation: NavGroup[] = [
  {
    items: [
      { title: "Explore", url: "/explore", icon: Search },
      { title: "Notebooks", url: "/notebooks", icon: BookText },
      { title: "Alert Rules", url: "/alert-rules", icon: ShieldAlert },
    ],
  },
]

const systemItems = [
  { title: "API Keys", url: "/system/api-keys", icon: KeyRound },
  { title: "Scheduled Jobs", url: "/system/jobs", icon: Clock },
  { title: "Data Management", url: "/system/data-management", icon: Database },
  { title: "Settings", url: "/system/settings", icon: Settings },
  { title: "About", url: "/system/about", icon: Info },
]

export function AppSidebar({ ...props }: React.ComponentProps<typeof Sidebar>) {
  const { url, props: pageProps } = usePage()
  const auth = (pageProps as { auth?: AuthSharedData }).auth
  const user = auth?.user

  // Persist the System submenu open/closed state across page navigations and
  // reloads. When the current route matches any system item, force the menu
  // open so the active item is always visible (e.g. deep-linking into
  // /system/api-keys).
  const isOnSystemRoute = systemItems.some(
    (item) => url === item.url || url.startsWith(item.url + "/")
  )
  const [systemOpenStored, setSystemOpenStored] = useLocalStorage(
    "o11ylite.sidebar.system-open",
    false
  )
  const systemOpen = isOnSystemRoute || systemOpenStored

  return (
    <Sidebar {...props}>
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              render={<Link href="/" />}
              className="data-[slot=sidebar-menu-button]:!p-1.5"
            >
              <img src={logo} alt="" className="!size-5" />
              <span className="text-base font-semibold">O11yLite</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        {navigation.map((group, index) => (
          <SidebarGroup key={group.label ?? index}>
            {group.label && (
              <SidebarGroupLabel>
                {group.icon && <group.icon className="mr-2 h-4 w-4" />}
                {group.label}
              </SidebarGroupLabel>
            )}
            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.map((item) => (
                  <SidebarMenuItem key={item.title}>
                    <SidebarMenuButton
                      render={<Link href={item.url} />}
                      isActive={url === item.url || url.startsWith(item.url + "/")}
                    >
                      <item.icon />
                      <span>{item.title}</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
        <SidebarGroup>
          <SidebarGroupLabel>System</SidebarGroupLabel>
          <SidebarMenu>
            <Collapsible
              open={systemOpen}
              onOpenChange={setSystemOpenStored}
              className="group/collapsible"
            >
              <SidebarMenuItem>
                <CollapsibleTrigger
                  render={<SidebarMenuButton tooltip="System" />}
                >
                  <Settings />
                  <span>System</span>
                  <ChevronRight className="ml-auto transition-transform duration-200 group-data-open/collapsible:rotate-90" />
                </CollapsibleTrigger>
                <CollapsibleContent>
                  <SidebarMenuSub>
                    {systemItems.map((item) => (
                      <SidebarMenuSubItem key={item.title}>
                        <SidebarMenuSubButton
                          render={<Link href={item.url} />}
                          isActive={url === item.url}
                        >
                          <item.icon />
                          <span>{item.title}</span>
                        </SidebarMenuSubButton>
                      </SidebarMenuSubItem>
                    ))}
                  </SidebarMenuSub>
                </CollapsibleContent>
              </SidebarMenuItem>
            </Collapsible>
          </SidebarMenu>
        </SidebarGroup>
      </SidebarContent>
      {user && (
        <SidebarFooter>
          <NavUser user={user} />
        </SidebarFooter>
      )}
      <SidebarRail />
    </Sidebar>
  )
}
