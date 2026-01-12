import { request, withNovelId } from './request'
import type { Relationship, RelationshipCreateDTO, RelationshipUpdateDTO } from '@/types/relationship'
import type { ID } from '@/types'

export const relationshipsApi = {
  getAll(): Promise<Relationship[]> {
    return request.get(withNovelId('/relationships'))
  },

  getByCharacter(characterId: ID): Promise<Relationship[]> {
    return request.get(withNovelId(`/relationships/character/${characterId}`))
  },

  create(data: RelationshipCreateDTO): Promise<Relationship> {
    return request.post(withNovelId('/relationships'), data)
  },

  update(id: ID, data: RelationshipUpdateDTO): Promise<Relationship> {
    return request.put(withNovelId(`/relationships/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/relationships/${id}`))
  }
}
