import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Novel, NovelCreateDTO, NovelUpdateDTO } from '@/types/novel'
import { novelsApi } from '@/api/novels'
import { setCurrentNovelId } from '@/api/request'
import { resetNovelData } from './novelData'

export const useNovelStore = defineStore('novel', () => {
  // 状态
  const novels = ref<Novel[]>([])
  const currentNovelId = ref<number | null>(null)
  const loading = ref(false)

  // Getters
  const currentNovel = computed(() =>
    novels.value.find(n => n.id === currentNovelId.value)
  )

  const hasCurrentNovel = computed(() => currentNovelId.value !== null)

  // Actions
  async function fetchNovels() {
    loading.value = true
    try {
      novels.value = await novelsApi.getAll()
    } finally {
      loading.value = false
    }
  }

  async function createNovel(data: NovelCreateDTO) {
    const novel = await novelsApi.create(data)
    novels.value.push(novel)
    return novel
  }

  async function updateNovel(id: number, data: NovelUpdateDTO) {
    const updated = await novelsApi.update(id, data)
    const index = novels.value.findIndex(n => n.id === id)
    if (index !== -1) {
      novels.value[index] = updated
    }
    return updated
  }

  async function deleteNovel(id: number) {
    await novelsApi.delete(id)
    novels.value = novels.value.filter(n => n.id !== id)
    if (currentNovelId.value === id) {
      setCurrentNovel(null)
    }
  }

  function setCurrentNovel(id: number | null) {
    setCurrentNovelId(id)
    if (currentNovelId.value !== id) resetNovelData()
    currentNovelId.value = id
  }

  return {
    novels,
    currentNovelId,
    currentNovel,
    hasCurrentNovel,
    loading,
    fetchNovels,
    createNovel,
    updateNovel,
    deleteNovel,
    setCurrentNovel
  }
})
