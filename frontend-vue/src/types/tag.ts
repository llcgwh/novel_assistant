import type { ID, Timestamp } from './index'

export interface Tag {
  id: ID
  name: string
  color?: string
  description?: string
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface TagCreateDTO {
  name: string
  color?: string
  description?: string
}

export interface TagUpdateDTO extends Partial<TagCreateDTO> {}
