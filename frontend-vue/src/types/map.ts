import type { ID, Timestamp } from './index'
import type { Tag } from './tag'

export interface MapLocation {
  id: ID
  name: string
  locationType?: string
  positionX?: number
  positionY?: number
  description?: string
  parentLocation?: { id: ID; name: string }
  locationImage?: string
  tags?: Tag[]
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface MapLocationCreateDTO {
  name: string
  locationType?: string
  positionX?: number
  positionY?: number
  description?: string
  parentLocation?: { id: ID }
  locationImage?: string
}

export interface MapLocationUpdateDTO extends Partial<MapLocationCreateDTO> {}
