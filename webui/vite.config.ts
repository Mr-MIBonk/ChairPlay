import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The dev server (npm run dev) sends the API to a running ChairPlay, started with CHAIRPLAY_UI_PORT set to the
// same port; open http://localhost:5173/?token=<the token in ChairPlay's log>.
const appPort = process.env.CHAIRPLAY_UI_PORT ?? '47800'

// The HUD's font files (HudPainter.kt), which the stylesheet takes as @fonts/<file>: the build copies them into
// its assets, so the page and the HUD draw with the same files and the repository holds them once.
const fonts = fileURLToPath(new URL('../src/main/resources/fonts', import.meta.url))

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@fonts': fonts },
  },
  build: {
    // Gradle passes --outDir build/webui/resources/ui, which goes into the jar.
    outDir: 'dist',
    assetsDir: 'assets',
    target: 'es2022',
  },
  server: {
    port: 5173,
    strictPort: true,
    // The fonts live outside webui/, which the dev server otherwise keeps to.
    fs: { allow: ['.', fonts] },
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
