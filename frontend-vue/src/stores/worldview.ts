import { getNovelContextVersion } from '@/api/request'
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { WorldviewEntry, WorldviewEntryCreateDTO, WorldviewEntryUpdateDTO } from '@/types/worldview'
import { worldviewApi } from '@/api/worldview'

export const useWorldviewStore = defineStore('worldview', () => {
  const entries = ref<WorldviewEntry[]>([])
  const loading = ref(false)
  const searchKeyword = ref('')
  const activeCategory = ref<string>('')

  const filteredEntries = computed(() => {
    let result = entries.value

    if (activeCategory.value) {
      result = result.filter(e => e.category === activeCategory.value)
    }

    if (searchKeyword.value.trim()) {
      const kw = searchKeyword.value.toLowerCase()
      result = result.filter(e =>
        e.name.toLowerCase().includes(kw) ||
        (e.content && e.content.toLowerCase().includes(kw))
      )
    }

    return result
  })

  async function fetchEntries(category?: string, keyword?: string) {
    const context = getNovelContextVersion()
    loading.value = true
    try {
      entries.value = await worldviewApi.getAll(category, keyword)
    } finally {
      if (context === getNovelContextVersion()) loading.value = false
    }
  }

  async function createEntry(data: WorldviewEntryCreateDTO) {
    const entry = await worldviewApi.create(data)
    entries.value.push(entry)
    return entry
  }

  async function updateEntry(id: number, data: WorldviewEntryUpdateDTO) {
    const updated = await worldviewApi.update(id, data)
    const index = entries.value.findIndex(e => e.id === id)
    if (index !== -1) {
      entries.value[index] = updated
    }
    return updated
  }

  async function deleteEntry(id: number) {
    await worldviewApi.delete(id)
    entries.value = entries.value.filter(e => e.id !== id)
  }

  async function setEntryTags(entryId: number, tagIds: number[]) {
    const updated = await worldviewApi.setTags(entryId, tagIds)
    const index = entries.value.findIndex(e => e.id === entryId)
    if (index !== -1) {
      entries.value[index] = updated
    }
    return updated
  }

  function setSearchKeyword(keyword: string) {
    searchKeyword.value = keyword
  }

  function setActiveCategory(category: string) {
    activeCategory.value = category
  }

  function $reset() {
    loading.value = false
    entries.value = []
    searchKeyword.value = ''
    activeCategory.value = ''
  }

  return {
    entries, loading, searchKeyword, activeCategory, filteredEntries,
    fetchEntries, createEntry, updateEntry, deleteEntry, setEntryTags,
    setSearchKeyword, setActiveCategory, $reset
  }
})
