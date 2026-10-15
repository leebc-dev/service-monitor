import { fileURLToPath, URL } from "node:url";
import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, fileURLToPath(new URL("..", import.meta.url)), "");
  const backend = `http://127.0.0.1:${env.BACKEND_PORT || 18180}`;
  return {
    plugins: [vue()],
    server: {
      host: "127.0.0.1",
      port: Number(env.FRONTEND_PORT || 15273),
      strictPort: true,
      proxy: {
        "/api": { target: backend },
        "/actuator/health": { target: backend },
      },
    },
    preview: {
      proxy: {
        "/api": { target: backend },
        "/actuator/health": { target: backend },
      },
    },
  };
});
