import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Outline, OutlineCreateDTO, OutlineUpdateDTO, OutlineStatus } from '@/types/outline'
import { outlinesApi } from '@/api/outlines'

export const useOutlinesStore = defineStore('outlines', () => {
  const outlines = ref<Outline[]>([])
  const loading = ref(false)
  const searchKeyword = ref('')
  const statusFilter = ref<OutlineStatus | ''>('')

  const filteredOutlines = computed(() => {
    let result = outlines.value

    if (statusFilter.value) {
      result = result.filter(o => o.status === statusFilter.value)
    }

    if (searchKeyword.value) {
      const keyword = searchKeyword.value.toLowerCase()
      result = result.filter(o =>
        o.title.toLowerCase().includes(keyword) ||
        o.content?.toLowerCase().includes(keyword)
      )
    }

    return result
  })

  async function fetchOutlines() {
    loading.value = true
    try {
      outlines.value = await outlinesApi.getAll()
    } finally {
      loading.value = false
    }
  }

  async function createOutline(data: OutlineCreateDTO) {
    const outline = await outlinesApi.create(data)
    outlines.value.push(outline)
    return outline
  }

  async function updateOutline(id: number, data: OutlineUpdateDTO) {
    const updated = await outlinesApi.update(id, data)
    const index = outlines.value.findIndex(o => o.id === id)
    if (index !== -1) {
      outlines.value[index] = updated
    }
    return updated
  }

  async function deleteOutline(id: number) {
    await outlinesApi.delete(id)
    outlines.value = outlines.value.filter(o => o.id !== id)
  }

  async function setOutlineTags(id: number, tagIds: number[]) {
    await outlinesApi.setTags(id, tagIds)
    await fetchOutlines()
  }

  function setSearchKeyword(keyword: string) {
    searchKeyword.value = keyword
  }

  function setStatusFilter(status: OutlineStatus | '') {
    statusFilter.value = status
  }

  function $reset() {
    outlines.value = []
    searchKeyword.value = ''
    statusFilter.value = ''
  }

  return {
    outlines, loading, searchKeyword, statusFilter, filteredOutlines,
    fetchOutlines, createOutline, updateOutline, deleteOutline,
    setOutlineTags, setSearchKeyword, setStatusFilter, $reset
  }
})
