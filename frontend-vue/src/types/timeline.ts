import type { ID, Timestamp } from './index'
import type { Tag } from './tag'
import type { Character } from './character'
import type { Scene } from './scene'
import type { Foreshadow } from './foreshadow'
import type { Outline } from './outline'

export interface TimelineEvent {
  id: ID
  title: string
  eventTime?: string
  realOrder?: number
  description?: string
  characters?: Character[]
  scenes?: Scene[]
  foreshadows?: Foreshadow[]
  outlines?: Outline[]
  tags?: Tag[]
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface TimelineEventCreateDTO {
  title: string
  eventTime?: string
  realOrder?: number
  description?: string
}

export interface TimelineEventUpdateDTO extends Partial<TimelineEventCreateDTO> {}

export interface TimelineEventRelations {
  characterIds: ID[]
  sceneIds: ID[]
  foreshadowIds: ID[]
  outlineIds: ID[]
}
