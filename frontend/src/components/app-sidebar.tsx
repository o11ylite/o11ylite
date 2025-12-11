import * as React from "react"
import {
  Search,
  LayoutDashboard,
  Activity,
  Bell,
  ShieldAlert,
  type LucideIcon,
} from "lucide-react"

import { SearchForm } from "@/components/search-form"
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
} from "@/components/ui/sidebar"

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
      { title: "Dashboards", url: "/dashboards", icon: LayoutDashboard },
    ],
  },
  {
    label: "Monitors",
    icon: Activity,
    items: [
      { title: "Rules", url: "/monitors/rules", icon: ShieldAlert },
      { title: "Notifications", url: "/monitors/notifications", icon: Bell },
    ],
  },
]

export function AppSidebar({ ...props }: React.ComponentProps<typeof Sidebar>) {
  return (
    <Sidebar {...props}>
      <SidebarHeader>
        <SearchForm />
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
                    <SidebarMenuButton asChild>
                      <a href={item.url}>
                        <item.icon />
                        <span>{item.title}</span>
                      </a>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>
      <SidebarRail />
    </Sidebar>
  )
}
