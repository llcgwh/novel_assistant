import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Relationship, RelationshipCreateDTO, RelationshipUpdateDTO } from '@/types/relationship'
import { relationshipsApi } from '@/api/relationships'

export const useRelationshipsStore = defineStore('relationships', () => {
  const relationships = ref<Relationship[]>([])
  const loading = ref(false)

  async function fetchRelationships() {
    loading.value = true
    try {
      relationships.value = await relationshipsApi.getAll()
    } finally {
      loading.value = false
    }
  }

  async function createRelationship(data: RelationshipCreateDTO) {
    const relationship = await relationshipsApi.create(data)
    relationships.value.push(relationship)
    return relationship
  }

  async function updateRelationship(id: number, data: RelationshipUpdateDTO) {
    const updated = await relationshipsApi.update(id, data)
    const index = relationships.value.findIndex(r => r.id === id)
    if (index !== -1) {
      relationships.value[index] = updated
    }
    return updated
  }

  async function deleteRelationship(id: number) {
    await relationshipsApi.delete(id)
    relationships.value = relationships.value.filter(r => r.id !== id)
  }

  function $reset() {
    relationships.value = []
  }

  return {
    relationships, loading,
    fetchRelationships, createRelationship, updateRelationship, deleteRelationship, $reset
  }
})
