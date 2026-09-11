import { getNovelContextVersion } from '@/api/request'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Tag, TagCreateDTO, TagUpdateDTO } from '@/types/tag'
import { tagsApi } from '@/api/tags'

export const useTagsStore = defineStore('tags', () => {
  const tags = ref<Tag[]>([])
  const loading = ref(false)

  async function fetchTags() {
    const context = getNovelContextVersion()
    loading.value = true
    try {
      tags.value = await tagsApi.getAll()
    } finally {
      if (context === getNovelContextVersion()) loading.value = false
    }
  }

  async function createTag(data: TagCreateDTO) {
    const tag = await tagsApi.create(data)
    tags.value.push(tag)
    return tag
  }

  async function updateTag(id: number, data: TagUpdateDTO) {
    const updated = await tagsApi.update(id, data)
    const index = tags.value.findIndex(t => t.id === id)
    if (index !== -1) {
      tags.value[index] = updated
    }
    return updated
  }

  async function deleteTag(id: number) {
    await tagsApi.delete(id)
    tags.value = tags.value.filter(t => t.id !== id)
  }

  function getTagById(id: number) {
    return tags.value.find(t => t.id === id)
  }

  function $reset() {
    loading.value = false
    tags.value = []
  }

  return {
    tags, loading,
    fetchTags, createTag, updateTag, deleteTag, getTagById, $reset
  }
})
