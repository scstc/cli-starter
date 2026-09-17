/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端 API 地址;默认 http://127.0.0.1:8090,同源网关部署置空即可 */
  readonly VITE_API_BASE?: string;
}
