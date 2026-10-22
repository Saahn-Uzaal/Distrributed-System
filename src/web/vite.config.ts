/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev-only: same-origin proxy tới các service local (spec §5/§12).
// Production: Nginx (:8080) phục vụ build và proxy /api/* — xem deploy/nginx.conf.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/auth': 'http://localhost:8081',
      '/api/drivers': 'http://localhost:8082',
      '/api/trips': 'http://localhost:8083',
      '/api/notifications': 'http://localhost:8083'
    }
  },
  test: {
    // Môi trường mặc định node; file test jsdom khai báo qua docblock @vitest-environment.
    setupFiles: ['./src/test/setup.ts']
  }
})
