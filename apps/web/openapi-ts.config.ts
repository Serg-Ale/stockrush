import { defineConfig } from '@hey-api/openapi-ts';

// Input is the contract the api publishes and its tests keep in sync. Output is committed, so a stale client shows up as a diff.
export default defineConfig({
  input: '../api/openapi.json',
  output: { path: 'src/app/api', clean: true },
  plugins: ['@hey-api/client-angular', '@hey-api/typescript', '@hey-api/sdk'],
});
