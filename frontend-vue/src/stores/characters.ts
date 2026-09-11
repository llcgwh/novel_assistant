import { getNovelContextVersion } from '@/api/request'
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Character, CharacterCreateDTO, CharacterUpdateDTO } from '@/types/character'
import { charactersApi } from '@/api/characters'

export const useCharactersStore = defineStore('characters', () => {
  // 状态
  const characters = ref<Character[]>([])
  const loading = ref(false)
  const searchKeyword = ref('')

  // Getters
  const filteredCharacters = computed(() => {
    if (!searchKeyword.value) return characters.value
    const keyword = searchKeyword.value.toLowerCase()
    return characters.value.filter(c =>
      c.name.toLowerCase().includes(keyword) ||
      c.role?.toLowerCase().includes(keyword) ||
      c.description?.toLowerCase().includes(keyword)
    )
  })

  // Actions
  async function fetchCharacters() {
    const context = getNovelContextVersion()
    loading.value = true
    try {
      characters.value = await charactersApi.getAll()
    } finally {
      if (context === getNovelContextVersion()) loading.value = false
    }
  }

  async function createCharacter(data: CharacterCreateDTO) {
    const character = await charactersApi.create(data)
    characters.value.push(character)
    return character
  }

  async function updateCharacter(id: number, data: CharacterUpdateDTO) {
    const updated = await charactersApi.update(id, data)
    const index = characters.value.findIndex(c => c.id === id)
    if (index !== -1) {
      characters.value[index] = updated
    }
    return updated
  }

  async function deleteCharacter(id: number) {
    await charactersApi.delete(id)
    characters.value = characters.value.filter(c => c.id !== id)
  }

  async function setCharacterTags(id: number, tagIds: number[]) {
    await charactersApi.setTags(id, tagIds)
    await fetchCharacters()
  }

  function setSearchKeyword(keyword: string) {
    searchKeyword.value = keyword
  }

  function $reset() {
    loading.value = false
    characters.value = []
    searchKeyword.value = ''
  }

  return {
    characters,
    loading,
    searchKeyword,
    filteredCharacters,
    fetchCharacters,
    createCharacter,
    updateCharacter,
    deleteCharacter,
    setCharacterTags,
    setSearchKeyword,
    $reset
  }
})
