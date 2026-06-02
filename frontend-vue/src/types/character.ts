import type { ID, Timestamp } from './index'
import type { Tag } from './tag'

export interface Character {
  id: ID
  name: string
  role?: string
  description?: string
  personality?: string
  appearance?: string
  background?: string
  portraitImage?: string
  tags?: Tag[]
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface CharacterCreateDTO {
  name: string
  role?: string
  description?: string
  personality?: string
  appearance?: string
  background?: string
  portraitImage?: string
}

export interface CharacterUpdateDTO extends Partial<CharacterCreateDTO> {}
