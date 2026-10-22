import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The dev server (npm run dev) sends the API to a running ChairPlay, started with CHAIRPLAY_UI_PORT set to the
// same port; open http://localhost:5173/?token=<the token in ChairPlay's log>.
const appPort = process.env.CHAIRPLAY_UI_PORT ?? '47800'

export default defineConfig({
  plugins: [react()],
  build: {
    // Gradle passes --outDir build/webui/resources/ui, which goes into the jar.
    outDir: 'dist',
    assetsDir: 'assets',
    target: 'es2022',
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      // changeOrigin sends the app its own host name, which it insists on.
      '/api': { target: `http://127.0.0.1:${appPort}`, changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
