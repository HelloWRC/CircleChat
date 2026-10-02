import { createAlova } from 'alova'
import adapterFetch from 'alova/fetch'
import VueHook from 'alova/vue'

export const api = createAlova({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  statesHook: VueHook,
  requestAdapter: adapterFetch(),
  timeout: 10_000,
  // Chat data should stay fresh; individual methods can opt into caching.
  cacheFor: {
    GET: 0,
  },
  responded: async (response) => {
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`)
    }

    const body = await response.text()
    if (!body) return undefined

    return response.headers.get('content-type')?.includes('json') ? JSON.parse(body) : body
  },
})
