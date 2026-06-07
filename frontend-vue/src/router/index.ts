import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'NovelSelector',
    component: () => import('@/views/NovelSelector.vue'),
    meta: { requiresNovel: false }
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { requiresNovel: false }
  },
  {
    path: '/novel/:novelId',
    component: () => import('@/views/MainLayout.vue'),
    meta: { requiresNovel: true },
    children: [
      {
        path: '',
        redirect: { name: 'Timeline' }
      },
      {
        path: 'timeline',
        name: 'Timeline',
        component: () => import('@/views/TimelineView.vue')
      },
      {
        path: 'characters',
        name: 'Characters',
        component: () => import('@/views/CharactersView.vue')
      },
      {
        path: 'scenes',
        name: 'Scenes',
        component: () => import('@/views/ScenesView.vue')
      },
      {
        path: 'foreshadows',
        name: 'Foreshadows',
        component: () => import('@/views/ForeshadowsView.vue')
      },
      {
        path: 'outlines',
        name: 'Outlines',
        component: () => import('@/views/OutlinesView.vue')
      },
      {
        path: 'map',
        name: 'Map',
        component: () => import('@/views/MapView.vue')
      },
      {
        path: 'relationships',
        name: 'Relationships',
        component: () => import('@/views/RelationshipsView.vue')
      },
      {
        path: 'tags',
        name: 'Tags',
        component: () => import('@/views/TagsView.vue')
      },
      {
        path: 'worldview',
        name: 'Worldview',
        component: () => import('@/views/WorldviewView.vue')
      },
      {
        path: 'search',
        name: 'SearchResults',
        component: () => import('@/views/SearchResultsView.vue')
      },
      {
        path: 'settings',
        name: 'NovelSettings',
        component: () => import('@/views/SettingsView.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, _from, next) => {
  const novelId = to.params.novelId as string

  if (to.meta.requiresNovel && !novelId) {
    next({ name: 'NovelSelector' })
  } else {
    next()
  }
})

export default router
