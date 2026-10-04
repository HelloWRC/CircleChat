import type { Pinia } from 'pinia'
import type { Router } from 'vue-router'
import { setAuthFailureHandler } from '@/api/instance'
import { useUserStore } from '@/stores/user'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
  }
}

export function getLoginRedirect(router: Router, redirect: unknown): string {
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//')) {
    return '/'
  }
  const resolved = router.resolve(redirect)
  if (!resolved.matched.length || resolved.meta.public) return '/'
  return resolved.fullPath
}

export function setupAuthGuard(router: Router, pinia: Pinia) {
  const user = useUserStore(pinia)

  router.beforeEach(async (to) => {
    try {
      await user.restoreSession()
    } catch {
      // A failed session check must never expose a protected view.
    }

    if (!to.meta.public && !user.isAuthenticated) {
      return { name: 'auth.login', query: { redirect: to.fullPath }, replace: true }
    }
    if ((to.name === 'auth.login' || to.name === 'auth.register') && user.isAuthenticated) {
      return getLoginRedirect(router, to.query.redirect)
    }
  })

  let checking = false
  setAuthFailureHandler((status) => {
    if (checking) return
    checking = true
    void (async () => {
      try {
        if (status === 401) user.clearSession()
        // Spring Security currently uses 403 for an expired session as well as denied access.
        else await user.refreshUser()

        const current = router.currentRoute.value
        if (!user.isAuthenticated && !current.meta.public) {
          await router.replace({ name: 'auth.login', query: { redirect: current.fullPath } })
        }
      } catch {
        // A network/server error does not establish that a 403 was an expired session.
      } finally {
        checking = false
      }
    })()
  })
}
