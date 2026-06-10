import { request, withNovelId } from './request'
import type { RelationshipGroup, RelationshipGroupCreateDTO, RelationshipGroupUpdateDTO } from '@/types/relationship'
import type { ID } from '@/types'

export const relationshipGroupsApi = {
  getAll(): Promise<RelationshipGroup[]> {
    return request.get(withNovelId('/relationship-groups'))
  },

  getById(id: ID): Promise<RelationshipGroup> {
    return request.get(withNovelId(`/relationship-groups/${id}`))
  },

  create(data: RelationshipGroupCreateDTO): Promise<RelationshipGroup> {
    return request.post(withNovelId('/relationship-groups'), data)
  },

  update(id: ID, data: RelationshipGroupUpdateDTO): Promise<RelationshipGroup> {
    return request.put(withNovelId(`/relationship-groups/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/relationship-groups/${id}`))
  },

  setCharacters(id: ID, characterIds: ID[]): Promise<RelationshipGroup> {
    return request.put(withNovelId(`/relationship-groups/${id}/characters`), characterIds)
  },

  addCharacter(id: ID, characterId: ID): Promise<RelationshipGroup> {
    return request.post(withNovelId(`/relationship-groups/${id}/characters/${characterId}`))
  },

  removeCharacter(id: ID, characterId: ID): Promise<RelationshipGroup> {
    return request.delete(withNovelId(`/relationship-groups/${id}/characters/${characterId}`))
  }
}
