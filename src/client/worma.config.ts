import { loadEnv } from 'vite'
import { defineConfig } from 'wormajs'
import { alova, rename } from 'wormajs/plugin'

// worma loads this config as CommonJS from the frontend project directory.
const env = loadEnv('development', __dirname, 'OPENAPI_')

export default defineConfig({
  generator: [
    {
      input: env.OPENAPI_INPUT || 'http://localhost:8080/v3/api-docs',
      output: 'src/api/generated',
      type: 'typescript',
      serverName: 'CircleChat',
      // Springdoc currently describes JSON responses as */*.
      responseMediaType: ['application/json', '*/*'],
      plugins: [alova(), rename({ scope: 'name', style: 'camelCase' })],
      handleApi(api) {
        // The shared alova instance already supplies the /api prefix.
        api.url = api.url.replace(/^\/api(?=\/|$)/, '') || '/'
        return api
      },
    },
  ],
})
