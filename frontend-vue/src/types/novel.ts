import type { ID, Timestamp } from './index'

export type NovelStatus = 'planning' | 'writing' | 'completed' | 'paused'

export interface Novel {
  id: ID
  title: string
  author?: string
  genre?: string
  status?: NovelStatus
  description?: string
  coverImage?: string
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface NovelCreateDTO {
  title: string
  author?: string
  genre?: string
  status?: NovelStatus
  description?: string
  coverImage?: string
}

export interface NovelUpdateDTO extends Partial<NovelCreateDTO> {}
