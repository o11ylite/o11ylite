import path from "path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  base: '/frontend',
  build: {
    manifest: true,
    outDir: 'dist',
    rollupOptions: {
      // overwrite default .html entry
      input: 'src/main.tsx',
    },
  },
  // Required for backend integration - ensures HMR works when served via Caddy
  server: {
    host: '127.0.0.1', // Need to match my dev Caddyfile
    origin: 'https://o11ylite.localhost',
    cors: true,
    hmr: {
      // HMR websocket goes through Caddy
      host: 'o11ylite.localhost',
      protocol: 'wss',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
