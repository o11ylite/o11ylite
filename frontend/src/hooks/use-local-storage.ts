import { useState, useEffect, useCallback } from "react"

/**
 * A hook that persists state to localStorage.
 *
 * @param key - The localStorage key
 * @param defaultValue - The default value if no stored value exists
 * @returns A stateful value and a function to update it (same API as useState)
 */
export function useLocalStorage<T>(
  key: string,
  defaultValue: T
): [T, (value: T | ((prev: T) => T)) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = localStorage.getItem(key)
      return stored !== null ? (JSON.parse(stored) as T) : defaultValue
    } catch {
      return defaultValue
    }
  })

  useEffect(() => {
    try {
      localStorage.setItem(key, JSON.stringify(value))
    } catch {
      // Ignore write errors (e.g., quota exceeded)
    }
  }, [key, value])

  const updateValue = useCallback(
    (newValue: T | ((prev: T) => T)) => {
      setValue((prev) =>
        typeof newValue === "function"
          ? (newValue as (prev: T) => T)(prev)
          : newValue
      )
    },
    []
  )

  return [value, updateValue]
}
