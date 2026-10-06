import { createAlova } from 'alova'
import adapterFetch from 'alova/fetch'
import VueHook from 'alova/vue'

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'HttpError'
  }
}

let onAuthFailure: ((status: number) => void) | undefined

export function setAuthFailureHandler(handler: (status: number) => void) {
  onAuthFailure = handler
}

const authEndpoints = new Set(['/v1/auth/login', '/v1/auth/logout', '/v1/users/register'])

export const api = createAlova({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  statesHook: VueHook,
  requestAdapter: adapterFetch(),
  timeout: 10_000,
  beforeRequest: (method) => {
    method.config.credentials = 'include'
  },
  // Chat data should stay fresh; individual methods can opt into caching.
  cacheFor: {
    GET: 0,
  },
  responded: async (response, method) => {
    if (!response.ok) {
      const isSessionCheck = method.url === '/v1/users/me' && method.type === 'GET'
      if (
        (response.status === 401 || response.status === 403) &&
        !authEndpoints.has(method.url) &&
        !isSessionCheck
      ) {
        onAuthFailure?.(response.status)
      }
      let message = `HTTP ${response.status}: ${response.statusText}`
      try {
        const body: unknown = await response.json()
        if (body && typeof body === 'object' && 'message' in body) {
          if (typeof body.message === 'string' && body.message.trim()) message = body.message
        }
      } catch {
        // Non-JSON errors still retain the HTTP status and fallback message.
      }
      throw new HttpError(response.status, message)
    }

    const body = await response.text()
    if (!body) return undefined

    return response.headers.get('content-type')?.includes('json') ? JSON.parse(body) : body
  },
})
