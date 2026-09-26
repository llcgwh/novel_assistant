import { ref, onScopeDispose } from 'vue'
import { searchApi } from '@/api/search'
import { emptySearchResults } from '@/utils/searchResults'

export function useSearch(load = searchApi.global) {
  const results = ref(emptySearchResults())
  const loading = ref(false)
  const query = ref('')
  const error = ref('')
  let revision = 0
  let controller: AbortController | undefined

  async function search(value: string) {
    const current = ++revision
    controller?.abort()
    controller = new AbortController()
    query.value = value.trim()
    error.value = ''
    results.value = emptySearchResults()
    loading.value = !!query.value
    if (!query.value) return
    try {
      const response = await load(query.value, controller.signal)
      if (current === revision) results.value = response
    } catch {
      if (current === revision) error.value = '搜索暂时失败，请检查后端连接后重试。'
    } finally {
      if (current === revision) loading.value = false
    }
  }

  onScopeDispose(() => { revision++; controller?.abort() })
  return { results, loading, query, error, search }
}
