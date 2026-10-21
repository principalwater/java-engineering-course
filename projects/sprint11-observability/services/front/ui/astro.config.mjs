import { defineConfig } from 'astro/config';

export default defineConfig({
  output: 'static',
  outDir: './build/site',
  build: { format: 'file', inlineStylesheets: 'never' },
  vite: { build: { assetsInlineLimit: 0 } },
});
