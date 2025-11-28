import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  base: process.env.ASSET_BASE_URL || '/',
  build: {
    manifest: true,
    outDir: 'dist',
    rollupOptions: {
      // overwrite default .html entry
      input: 'src/main.tsx',
    },
  },
  // Required for backend integration - ensures HMR works when served from Clojure
  server: {
    origin: 'http://localhost:5173',
    cors: true,
  },
})
