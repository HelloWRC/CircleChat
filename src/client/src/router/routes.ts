import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/base.vue'),
    redirect: { name: 'chat' },
    children: [
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/ChatLayout.vue'),
        redirect: (to) => ({
          name: 'chat.conversation',
          params: { id: '0' },
          query: to.query,
          hash: to.hash,
        }),
        children: [
          {
            path: ':id(0|[1-9]\\d*)',
            name: 'chat.conversation',
            component: () => import('@/views/Chat.vue'),
            props: true,
          },
        ],
      },
      {
        path: 'friends',
        name: 'friends',
        component: () => import('@/views/friends/base.vue'),
        children: [
          {
            path: 'new',
            name: 'friends.new',
            component: () => import('@/views/friends/New.vue'),
          },
          {
            path: 'details/:username',
            name: 'friends.details',
            component: () => import('@/views/friends/Details.vue'),
            props: true,
          },
        ],
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/settings/base.vue'),
      },
    ],
  },
  {
    path: '/auth',
    name: 'auth',
    component: () => import('@/views/auth/base.vue'),
    redirect: { name: 'auth.login' },
    meta: { public: true },
    children: [
      {
        path: 'login',
        name: 'auth.login',
        component: () => import('@/views/auth/Login.vue'),
      },
      {
        path: 'register',
        name: 'auth.register',
        component: () => import('@/views/auth/Register.vue'),
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]
