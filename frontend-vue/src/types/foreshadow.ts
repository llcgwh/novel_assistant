import type { ID, Timestamp } from './index'
import type { Tag } from './tag'

export type ForeshadowStatus = 'UNREVEALED' | 'REVEALED' | 'ABANDONED'

export interface Foreshadow {
  id: ID
  title: string
  content?: string
  status: ForeshadowStatus
  plantedChapter?: string
  revealedChapter?: string
  tags?: Tag[]
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface ForeshadowCreateDTO {
  title: string
  content?: string
  status?: ForeshadowStatus
  plantedChapter?: string
  revealedChapter?: string
}

export interface ForeshadowUpdateDTO extends Partial<ForeshadowCreateDTO> {}
