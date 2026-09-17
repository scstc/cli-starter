import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// dev 端口 5173 / preview 端口 8000 都在后端 CORS 白名单内
// (server/application.yml 的 starter.cors.allowed-origins)
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
  preview: { port: 8000, host: true },
});
