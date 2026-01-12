import { request, withNovelId } from './request'
import type { Tag, TagCreateDTO, TagUpdateDTO } from '@/types/tag'
import type { ID } from '@/types'

export const tagsApi = {
  getAll(): Promise<Tag[]> {
    return request.get(withNovelId('/tags'))
  },

  getById(id: ID): Promise<Tag> {
    return request.get(withNovelId(`/tags/${id}`))
  },

  create(data: TagCreateDTO): Promise<Tag> {
    return request.post(withNovelId('/tags'), data)
  },

  update(id: ID, data: TagUpdateDTO): Promise<Tag> {
    return request.put(withNovelId(`/tags/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/tags/${id}`))
  },

  search(name: string): Promise<Tag[]> {
    return request.get(withNovelId('/tags/search'), { params: { name } })
  }
}
