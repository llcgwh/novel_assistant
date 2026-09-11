import { getNovelContextVersion } from '@/api/request'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RelationshipGroup, RelationshipGroupCreateDTO, RelationshipGroupUpdateDTO } from '@/types/relationship'
import { relationshipGroupsApi } from '@/api/relationshipGroups'
import type { ID } from '@/types'

export const useRelationshipGroupsStore = defineStore('relationshipGroups', () => {
  const groups = ref<RelationshipGroup[]>([])
  const loading = ref(false)

  async function fetchGroups() {
    const context = getNovelContextVersion()
    loading.value = true
    try {
      groups.value = await relationshipGroupsApi.getAll()
    } finally {
      if (context === getNovelContextVersion()) loading.value = false
    }
  }

  async function createGroup(data: RelationshipGroupCreateDTO) {
    const group = await relationshipGroupsApi.create(data)
    groups.value.push(group)
    return group
  }

  async function updateGroup(id: ID, data: RelationshipGroupUpdateDTO) {
    const updated = await relationshipGroupsApi.update(id, data)
    const index = groups.value.findIndex(g => g.id === id)
    if (index !== -1) {
      groups.value[index] = updated
    }
    return updated
  }

  async function deleteGroup(id: ID) {
    await relationshipGroupsApi.delete(id)
    groups.value = groups.value.filter(g => g.id !== id)
  }

  async function setGroupCharacters(groupId: ID, characterIds: ID[]) {
    const updated = await relationshipGroupsApi.setCharacters(groupId, characterIds)
    const index = groups.value.findIndex(g => g.id === groupId)
    if (index !== -1) {
      groups.value[index] = updated
    }
    return updated
  }

  /** Get all group IDs that contain a character (including through parent groups implicitly — we return direct membership) */
  function getGroupsForCharacter(characterId: ID): RelationshipGroup[] {
    return groups.value.filter(g =>
      g.characters?.some(c => c.id === characterId)
    )
  }

  function $reset() {
    loading.value = false
    groups.value = []
  }

  return {
    groups, loading,
    fetchGroups, createGroup, updateGroup, deleteGroup, setGroupCharacters,
    getGroupsForCharacter, $reset
  }
})
