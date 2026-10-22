import { defineConfig } from 'vite'
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.API_PROXY || 'http://localhost:8080',
      '/actuator': process.env.API_PROXY || 'http://localhost:8080',
      '/swagger-ui': process.env.API_PROXY || 'http://localhost:8080',
      '/api-docs': process.env.API_PROXY || 'http://localhost:8080',
      '/webjars': process.env.API_PROXY || 'http://localhost:8080',
    },
  },
})
