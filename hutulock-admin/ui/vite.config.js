import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': resolve(__dirname, 'src') }
  },
  build: {
    // 构建产物输出到 server 模块的 resources，打包进 jar
    outDir: '../src/main/resources/admin-ui',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // 固定 chunk 文件名，避免 hash 变化导致 Java 静态服务找不到文件
        entryFileNames: 'assets/[name].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]'
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:9091', changeOrigin: true }
    }
  }
})
