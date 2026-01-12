import { request, withNovelId } from './request'
import type { MapLocation, MapLocationCreateDTO, MapLocationUpdateDTO } from '@/types/map'
import type { ID } from '@/types'

export const mapLocationsApi = {
  getAll(): Promise<MapLocation[]> {
    return request.get(withNovelId('/map-locations'))
  },

  getById(id: ID): Promise<MapLocation> {
    return request.get(withNovelId(`/map-locations/${id}`))
  },

  create(data: MapLocationCreateDTO): Promise<MapLocation> {
    return request.post(withNovelId('/map-locations'), data)
  },

  update(id: ID, data: MapLocationUpdateDTO): Promise<MapLocation> {
    return request.put(withNovelId(`/map-locations/${id}`), data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/map-locations/${id}`))
  },

  search(keyword: string): Promise<MapLocation[]> {
    return request.get(withNovelId('/map-locations/search'), { params: { keyword } })
  },

  getByType(type: string): Promise<MapLocation[]> {
    return request.get(withNovelId(`/map-locations/type/${type}`))
  },

  setTags(id: ID, tagIds: ID[]): Promise<void> {
    return request.put(withNovelId(`/map-locations/${id}/tags`), tagIds)
  },

  addTag(id: ID, tagId: ID): Promise<void> {
    return request.post(withNovelId(`/map-locations/${id}/tags/${tagId}`))
  },

  removeTag(id: ID, tagId: ID): Promise<void> {
    return request.delete(withNovelId(`/map-locations/${id}/tags/${tagId}`))
  }
}
