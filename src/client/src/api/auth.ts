import { api } from './instance'

// This endpoint is provided by Spring Security's logout filter rather than an MVC controller.
export function logout() {
  return api.Post<void>('/v1/auth/logout')
}
