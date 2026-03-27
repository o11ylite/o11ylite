import { useState } from "react"

export function useSelection<T extends { name: string }>(items: T[]) {
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const toggle = (name: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  const toggleAll = () => {
    if (selected.size === items.length) {
      setSelected(new Set())
    } else {
      setSelected(new Set(items.map((i) => i.name)))
    }
  }

  const clear = () => setSelected(new Set())

  const allSelected = items.length > 0 && selected.size === items.length
  const someSelected = selected.size > 0 && selected.size < items.length

  return { selected, toggle, toggleAll, clear, allSelected, someSelected }
}
