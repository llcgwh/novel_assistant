import { request, withNovelId } from './request'
import type { Scene, SceneCreateDTO, SceneUpdateDTO } from '@/types/scene'
import type { ID } from '@/types'

export const scenesApi = {
  getAll(): Promise<Scene[]> {
    return request.get(withNovelId('/scenes'))
  },

  getById(id: ID): Promise<Scene> {
    return request.get(withNovelId(`/scenes/${id}`))
  },

  create(data: SceneCreateDTO): Promise<Scene> {
    return request.post(withNovelId('/scenes'), data)
  },

  update(id: ID, data: SceneUpdateDTO): Promise<Scene> {
    return request.put(withNovelId(`/scenes/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/scenes/${id}`))
  },

  search(keyword: string): Promise<Scene[]> {
    return request.get(withNovelId('/scenes/search'), { params: { keyword } })
  },

  setTags(id: ID, tagIds: ID[]): Promise<void> {
    return request.put(withNovelId(`/scenes/${id}/tags`), tagIds)
  },

  addTag(id: ID, tagId: ID): Promise<void> {
    return request.post(withNovelId(`/scenes/${id}/tags/${tagId}`))
  },

  removeTag(id: ID, tagId: ID): Promise<void> {
    return request.delete(withNovelId(`/scenes/${id}/tags/${tagId}`))
  }
}
