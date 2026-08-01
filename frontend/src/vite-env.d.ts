/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_TENANT_ID?: string;
  readonly VITE_TENANTS?: string;
  readonly VITE_LOCAL_AUTH?: string;
  readonly VITE_AUTH_SUBJECT?: string;
  readonly VITE_AUTH_ROLES?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
