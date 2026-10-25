import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Same-origin dev setup: the browser only ever talks to :5173; API and OAuth go to Spring.
const backend = { target: 'http://localhost:8080', changeOrigin: true };

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': backend,
      '/oauth2': backend,
      '/login/oauth2': backend,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    css: false,
    testTimeout: 30000,
  },
});
