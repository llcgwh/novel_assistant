import { request, withNovelId } from './request'
import type { WorldviewEntry, WorldviewEntryCreateDTO, WorldviewEntryUpdateDTO } from '@/types/worldview'
import type { ID } from '@/types'

export const worldviewApi = {
  getAll(category?: string, keyword?: string): Promise<WorldviewEntry[]> {
    const params: Record<string, string> = {}
    if (category) params.category = category
    if (keyword) params.keyword = keyword
    return request.get(withNovelId('/worldview'), { params })
  },

  getById(id: ID): Promise<WorldviewEntry> {
    return request.get(withNovelId(`/worldview/${id}`))
  },

  create(data: WorldviewEntryCreateDTO): Promise<WorldviewEntry> {
    return request.post(withNovelId('/worldview'), data)
  },

  update(id: ID, data: WorldviewEntryUpdateDTO): Promise<WorldviewEntry> {
    return request.put(withNovelId(`/worldview/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/worldview/${id}`))
  },

  setTags(id: ID, tagIds: ID[]): Promise<WorldviewEntry> {
    return request.put(withNovelId(`/worldview/${id}/tags`), tagIds)
  },

  addTag(id: ID, tagId: ID): Promise<WorldviewEntry> {
    return request.post(withNovelId(`/worldview/${id}/tags/${tagId}`))
  },

  removeTag(id: ID, tagId: ID): Promise<WorldviewEntry> {
    return request.delete(withNovelId(`/worldview/${id}/tags/${tagId}`))
  },

  addCharacterRelation(id: ID, characterId: ID): Promise<WorldviewEntry> {
    return request.post(withNovelId(`/worldview/${id}/characters/${characterId}`))
  },

  removeCharacterRelation(id: ID, characterId: ID): Promise<WorldviewEntry> {
    return request.delete(withNovelId(`/worldview/${id}/characters/${characterId}`))
  },

  addSceneRelation(id: ID, sceneId: ID): Promise<WorldviewEntry> {
    return request.post(withNovelId(`/worldview/${id}/scenes/${sceneId}`))
  },

  removeSceneRelation(id: ID, sceneId: ID): Promise<WorldviewEntry> {
    return request.delete(withNovelId(`/worldview/${id}/scenes/${sceneId}`))
  },

  addMapLocationRelation(id: ID, locationId: ID): Promise<WorldviewEntry> {
    return request.post(withNovelId(`/worldview/${id}/locations/${locationId}`))
  },

  removeMapLocationRelation(id: ID, locationId: ID): Promise<WorldviewEntry> {
    return request.delete(withNovelId(`/worldview/${id}/locations/${locationId}`))
  }
}
