import { getNovelContextVersion } from '@/api/request'
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Foreshadow, ForeshadowCreateDTO, ForeshadowUpdateDTO, ForeshadowStatus } from '@/types/foreshadow'
import { foreshadowsApi } from '@/api/foreshadows'

export const useForeshadowsStore = defineStore('foreshadows', () => {
  const foreshadows = ref<Foreshadow[]>([])
  const loading = ref(false)
  const searchKeyword = ref('')
  const statusFilter = ref<ForeshadowStatus | ''>('')

  const filteredForeshadows = computed(() => {
    let result = foreshadows.value

    if (statusFilter.value) {
      result = result.filter(f => f.status === statusFilter.value)
    }

    if (searchKeyword.value) {
      const keyword = searchKeyword.value.toLowerCase()
      result = result.filter(f =>
        f.title.toLowerCase().includes(keyword) ||
        f.content?.toLowerCase().includes(keyword)
      )
    }

    return result
  })

  async function fetchForeshadows() {
    const context = getNovelContextVersion()
    loading.value = true
    try {
      foreshadows.value = await foreshadowsApi.getAll()
    } finally {
      if (context === getNovelContextVersion()) loading.value = false
    }
  }

  async function createForeshadow(data: ForeshadowCreateDTO) {
    const foreshadow = await foreshadowsApi.create(data)
    foreshadows.value.push(foreshadow)
    return foreshadow
  }

  async function updateForeshadow(id: number, data: ForeshadowUpdateDTO) {
    const updated = await foreshadowsApi.update(id, data)
    const index = foreshadows.value.findIndex(f => f.id === id)
    if (index !== -1) {
      foreshadows.value[index] = updated
    }
    return updated
  }

  async function deleteForeshadow(id: number) {
    await foreshadowsApi.delete(id)
    foreshadows.value = foreshadows.value.filter(f => f.id !== id)
  }

  async function setForeshadowTags(id: number, tagIds: number[]) {
    await foreshadowsApi.setTags(id, tagIds)
    await fetchForeshadows()
  }

  function setSearchKeyword(keyword: string) {
    searchKeyword.value = keyword
  }

  function setStatusFilter(status: ForeshadowStatus | '') {
    statusFilter.value = status
  }

  function $reset() {
    loading.value = false
    foreshadows.value = []
    searchKeyword.value = ''
    statusFilter.value = ''
  }

  return {
    foreshadows, loading, searchKeyword, statusFilter, filteredForeshadows,
    fetchForeshadows, createForeshadow, updateForeshadow, deleteForeshadow,
    setForeshadowTags, setSearchKeyword, setStatusFilter, $reset
  }
})
