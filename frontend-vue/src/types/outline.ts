import type { ID, Timestamp } from './index'
import type { Tag } from './tag'

export type OutlineStatus = 'planning' | 'writing' | 'completed'

export interface Outline {
  id: ID
  title: string
  content?: string
  status: OutlineStatus
  chapterNumber?: number
  plotOrder?: number
  tags?: Tag[]
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface OutlineCreateDTO {
  title: string
  content?: string
  status?: OutlineStatus
  chapterNumber?: number
  plotOrder?: number
}

export interface OutlineUpdateDTO extends Partial<OutlineCreateDTO> {}
