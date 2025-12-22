import path from "path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

const hostname = process.env.O11YLITE_DEV_HOSTNAME || 'o11ylite.localhost'
const vitePort = parseInt(process.env.VITE_PORT || '5173')

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
    port: vitePort,
    origin: `https://${hostname}`,
    cors: true,
    hmr: {
      // HMR websocket goes through Caddy
      host: hostname,
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
