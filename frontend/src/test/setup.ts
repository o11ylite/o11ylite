import "@testing-library/jest-dom/vitest"

// Mock window.matchMedia for jsdom (used by use-mobile hook)
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})

// Mock ResizeObserver for jsdom (used by cmdk/Command component)
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock

// Mock scrollIntoView for jsdom (used by cmdk/Command component)
Element.prototype.scrollIntoView = () => {}
