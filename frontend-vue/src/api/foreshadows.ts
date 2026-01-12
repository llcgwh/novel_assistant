import { request, withNovelId } from './request'
import type { Foreshadow, ForeshadowCreateDTO, ForeshadowUpdateDTO, ForeshadowStatus } from '@/types/foreshadow'
import type { ID } from '@/types'

export const foreshadowsApi = {
  getAll(): Promise<Foreshadow[]> {
    return request.get(withNovelId('/foreshadows'))
  },

  getById(id: ID): Promise<Foreshadow> {
    return request.get(withNovelId(`/foreshadows/${id}`))
  },

  create(data: ForeshadowCreateDTO): Promise<Foreshadow> {
    return request.post(withNovelId('/foreshadows'), data)
  },

  update(id: ID, data: ForeshadowUpdateDTO): Promise<Foreshadow> {
    return request.put(withNovelId(`/foreshadows/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/foreshadows/${id}`))
  },

  search(keyword: string): Promise<Foreshadow[]> {
    return request.get(withNovelId('/foreshadows/search'), { params: { keyword } })
  },

  getByStatus(status: ForeshadowStatus): Promise<Foreshadow[]> {
    return request.get(withNovelId(`/foreshadows/status/${status}`))
  },

  setTags(id: ID, tagIds: ID[]): Promise<void> {
    return request.put(withNovelId(`/foreshadows/${id}/tags`), tagIds)
  },

  addTag(id: ID, tagId: ID): Promise<void> {
    return request.post(withNovelId(`/foreshadows/${id}/tags/${tagId}`))
  },

  removeTag(id: ID, tagId: ID): Promise<void> {
    return request.delete(withNovelId(`/foreshadows/${id}/tags/${tagId}`))
  }
}
