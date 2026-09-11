import type { Router } from 'vue-router'
import { parseNovelId } from '@/api/request'
import { useNovelStore } from '@/stores/novel'

export function installNovelContext(router: Router) {
  router.beforeEach(to => {
    if (to.meta.requiresNovel && parseNovelId(to.params.novelId) === null) {
      return { name: 'NovelSelector' }
    }
  })

  // 导航确认后、子页面挂载前切换；被取消的导航不影响当前小说。
  router.afterEach((to, _from, failure) => {
    if (!failure) {
      useNovelStore().setCurrentNovel(to.meta.requiresNovel ? parseNovelId(to.params.novelId) : null)
    }
  })
}
