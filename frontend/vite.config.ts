import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The console is served by the load balancer on the same origin as the API, so every request
// is same-origin: no CORS, and no request to another host.
export default defineConfig({
  plugins: [react()],
  build: { sourcemap: false, target: 'es2022' },
  // Unit tests only; e2e/ is the Playwright browser test (npx playwright test).
  test: { include: ['src/**/*.test.ts'] },
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080', '/health': 'http://localhost:8080' },
  },
});
