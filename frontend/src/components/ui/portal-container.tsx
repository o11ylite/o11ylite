"use client"

import { createContext, useContext } from "react"
import type { RefObject } from "react"

// Radix modal dialogs set `pointer-events: none` on <body> and trap focus to
// their own subtree, so popups (e.g. Combobox) that portal to <body> become
// unclickable and unfocusable. Dialogs provide their content element through
// this context so popups can portal into it instead.
export const PortalContainerContext =
  createContext<RefObject<HTMLElement | null> | null>(null)

export function usePortalContainer() {
  return useContext(PortalContainerContext)
}
