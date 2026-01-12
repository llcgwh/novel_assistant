import type { ID, Timestamp } from './index'
import type { Tag } from './tag'

export type OutlineStatus = 'PLANNING' | 'WRITING' | 'COMPLETED'

export interface Outline {
  id: ID
  title: string
  content?: string
  status: OutlineStatus
  sortOrder?: number
  tags?: Tag[]
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface OutlineCreateDTO {
  title: string
  content?: string
  status?: OutlineStatus
  sortOrder?: number
}

export interface OutlineUpdateDTO extends Partial<OutlineCreateDTO> {}
