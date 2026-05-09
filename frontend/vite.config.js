import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  root: 'src',
  build: {
    outDir: '../dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'src/index.html'),
        chat: resolve(__dirname, 'src/chat.html'),
        stats: resolve(__dirname, 'src/stats.html'),
        face: resolve(__dirname, 'src/face.html'),
      },
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://124.221.117.155:8000',
        changeOrigin: true,
      },
    },
  },
});
