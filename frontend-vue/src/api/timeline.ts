import { request, withNovelId } from './request'
import type { TimelineEvent, TimelineEventCreateDTO, TimelineEventUpdateDTO, TimelineEventRelations } from '@/types/timeline'
import type { ID } from '@/types'

export const timelineApi = {
  getAll(): Promise<TimelineEvent[]> {
    return request.get(withNovelId('/timeline-events'))
  },

  getById(id: ID): Promise<TimelineEvent> {
    return request.get(withNovelId(`/timeline-events/${id}`))
  },

  create(data: TimelineEventCreateDTO): Promise<TimelineEvent> {
    return request.post(withNovelId('/timeline-events'), data)
  },

  update(id: ID, data: TimelineEventUpdateDTO): Promise<TimelineEvent> {
    return request.put(withNovelId(`/timeline-events/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/timeline-events/${id}`))
  },

  search(keyword: string): Promise<TimelineEvent[]> {
    return request.get(withNovelId('/timeline-events/search'), { params: { keyword } })
  },

  addCharacters(id: ID, characterIds: ID[]): Promise<void> {
    return request.post(withNovelId(`/timeline-events/${id}/characters`), characterIds)
  },

  addScenes(id: ID, sceneIds: ID[]): Promise<void> {
    return request.post(withNovelId(`/timeline-events/${id}/scenes`), sceneIds)
  },

  addForeshadows(id: ID, foreshadowIds: ID[]): Promise<void> {
    return request.post(withNovelId(`/timeline-events/${id}/foreshadows`), foreshadowIds)
  },

  addOutlines(id: ID, outlineIds: ID[]): Promise<void> {
    return request.post(withNovelId(`/timeline-events/${id}/outlines`), outlineIds)
  },

  setRelations(id: ID, relations: TimelineEventRelations): Promise<void> {
    return request.put(withNovelId(`/timeline-events/${id}/relations`), relations)
  },

  setTags(id: ID, tagIds: ID[]): Promise<void> {
    return request.put(withNovelId(`/timeline-events/${id}/tags`), tagIds)
  },

  addTag(id: ID, tagId: ID): Promise<void> {
    return request.post(withNovelId(`/timeline-events/${id}/tags/${tagId}`))
  },

  removeTag(id: ID, tagId: ID): Promise<void> {
    return request.delete(withNovelId(`/timeline-events/${id}/tags/${tagId}`))
  }
}
