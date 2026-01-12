import { request, withNovelId } from './request'
import type { Character, CharacterCreateDTO, CharacterUpdateDTO } from '@/types/character'
import type { ID } from '@/types'

export const charactersApi = {
  getAll(): Promise<Character[]> {
    return request.get(withNovelId('/characters'))
  },

  getById(id: ID): Promise<Character> {
    return request.get(withNovelId(`/characters/${id}`))
  },

  create(data: CharacterCreateDTO): Promise<Character> {
    return request.post(withNovelId('/characters'), data)
  },

  update(id: ID, data: CharacterUpdateDTO): Promise<Character> {
    return request.put(withNovelId(`/characters/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/characters/${id}`))
  },

  search(keyword: string): Promise<Character[]> {
    return request.get(withNovelId('/characters/search'), { params: { keyword } })
  },

  setTags(id: ID, tagIds: ID[]): Promise<void> {
    return request.put(withNovelId(`/characters/${id}/tags`), tagIds)
  },

  addTag(id: ID, tagId: ID): Promise<void> {
    return request.post(withNovelId(`/characters/${id}/tags/${tagId}`))
  },

  removeTag(id: ID, tagId: ID): Promise<void> {
    return request.delete(withNovelId(`/characters/${id}/tags/${tagId}`))
  }
}
