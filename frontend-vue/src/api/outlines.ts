import { request, withNovelId } from './request'
import type { Outline, OutlineCreateDTO, OutlineUpdateDTO, OutlineStatus } from '@/types/outline'
import type { ID } from '@/types'

export const outlinesApi = {
  getAll(): Promise<Outline[]> {
    return request.get(withNovelId('/outlines'))
  },

  getById(id: ID): Promise<Outline> {
    return request.get(withNovelId(`/outlines/${id}`))
  },

  create(data: OutlineCreateDTO): Promise<Outline> {
    return request.post(withNovelId('/outlines'), data)
  },

  update(id: ID, data: OutlineUpdateDTO): Promise<Outline> {
    return request.put(withNovelId(`/outlines/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/outlines/${id}`))
  },

  search(keyword: string): Promise<Outline[]> {
    return request.get(withNovelId('/outlines/search'), { params: { keyword } })
  },

  getByStatus(status: OutlineStatus): Promise<Outline[]> {
    return request.get(withNovelId(`/outlines/status/${status}`))
  },

  setTags(id: ID, tagIds: ID[]): Promise<void> {
    return request.put(withNovelId(`/outlines/${id}/tags`), tagIds)
  },

  addTag(id: ID, tagId: ID): Promise<void> {
    return request.post(withNovelId(`/outlines/${id}/tags/${tagId}`))
  },

  removeTag(id: ID, tagId: ID): Promise<void> {
    return request.delete(withNovelId(`/outlines/${id}/tags/${tagId}`))
  }
}
