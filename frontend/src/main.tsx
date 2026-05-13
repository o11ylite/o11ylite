import 'vite/modulepreload-polyfill'
import { createInertiaApp, router, type ResolvedComponent } from '@inertiajs/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 0,
      refetchOnWindowFocus: false,
      retry: false,
    },
  },
})

// Preserve time range query params across navigations
router.on('before', (event) => {
  const currentParams = new URLSearchParams(window.location.search)
  const from = currentParams.get('from')
  const to = currentParams.get('to')

  if (from && to) {
    const url = event.detail.visit.url
    // Only add if not already present in the target URL
    if (!url.searchParams.has('from')) {
      url.searchParams.set('from', from)
      url.searchParams.set('to', to)
    }
  }
})

void createInertiaApp({
  resolve: async (name) => {
    const pages = import.meta.glob<{ default: ResolvedComponent }>([
      './pages/**/*.tsx',
      '!./pages/**/*.test.tsx',
    ])
    const page = await pages[`./pages/${name}.tsx`]()
    return page.default
  },
  setup({ el, App, props }) {
    createRoot(el).render(
      <QueryClientProvider client={queryClient}>
        <App {...props} />
      </QueryClientProvider>
    )
  },
})
