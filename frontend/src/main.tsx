import 'vite/modulepreload-polyfill'
import { createInertiaApp } from '@inertiajs/react'
import { createRoot } from 'react-dom/client'
import './index.css'

void createInertiaApp({
  resolve: async (name) => {
    const pages = import.meta.glob('./pages/**/*.tsx')
    const page = await pages[`./pages/${name}.tsx`]()
    return page
  },
  setup({ el, App, props }) {
    createRoot(el).render(<App {...props} />)
  },
})
