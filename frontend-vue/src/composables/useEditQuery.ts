import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { parseNovelId } from '@/api/request'

// Wait for the list to load, then open the linked record once. Later store refreshes
// must not overwrite a form that the user is already editing.
export function useEditQuery<T extends { id: number }>(items: () => T[], edit: (item: T) => void) {
  const route = useRoute()
  let opened = ''
  watch([() => route.query.edit, items], () => {
    const id = parseNovelId(route.query.edit)
    if (id === null) { opened = ''; return }
    const key = `${route.params.novelId}/${id}`
    if (key === opened) return
    const item = items().find(item => item.id === id)
    if (item) { opened = key; edit(item) }
  }, { immediate: true })
}
