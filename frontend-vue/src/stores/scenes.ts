import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Scene, SceneCreateDTO, SceneUpdateDTO } from '@/types/scene'
import { scenesApi } from '@/api/scenes'

export const useScenesStore = defineStore('scenes', () => {
  const scenes = ref<Scene[]>([])
  const loading = ref(false)
  const searchKeyword = ref('')

  const filteredScenes = computed(() => {
    if (!searchKeyword.value) return scenes.value
    const keyword = searchKeyword.value.toLowerCase()
    return scenes.value.filter(s =>
      s.name.toLowerCase().includes(keyword) ||
      s.location?.toLowerCase().includes(keyword) ||
      s.description?.toLowerCase().includes(keyword)
    )
  })

  async function fetchScenes() {
    loading.value = true
    try {
      scenes.value = await scenesApi.getAll()
    } finally {
      loading.value = false
    }
  }

  async function createScene(data: SceneCreateDTO) {
    const scene = await scenesApi.create(data)
    scenes.value.push(scene)
    return scene
  }

  async function updateScene(id: number, data: SceneUpdateDTO) {
    const updated = await scenesApi.update(id, data)
    const index = scenes.value.findIndex(s => s.id === id)
    if (index !== -1) {
      scenes.value[index] = updated
    }
    return updated
  }

  async function deleteScene(id: number) {
    await scenesApi.delete(id)
    scenes.value = scenes.value.filter(s => s.id !== id)
  }

  async function setSceneTags(id: number, tagIds: number[]) {
    await scenesApi.setTags(id, tagIds)
    await fetchScenes()
  }

  function setSearchKeyword(keyword: string) {
    searchKeyword.value = keyword
  }

  function $reset() {
    scenes.value = []
    searchKeyword.value = ''
  }

  return {
    scenes,
    loading,
    searchKeyword,
    filteredScenes,
    fetchScenes,
    createScene,
    updateScene,
    deleteScene,
    setSceneTags,
    setSearchKeyword,
    $reset
  }
})
