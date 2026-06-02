import type { ID, Timestamp } from './index'
import type { Tag } from './tag'

export type ForeshadowStatus = 'pending' | 'revealed' | 'abandoned'

export interface Foreshadow {
  id: ID
  title: string
  content?: string
  status: ForeshadowStatus
  laidAt?: string
  revealedAt?: string
  tags?: Tag[]
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface ForeshadowCreateDTO {
  title: string
  content?: string
  status?: ForeshadowStatus
  laidAt?: string
  revealedAt?: string
}

export interface ForeshadowUpdateDTO extends Partial<ForeshadowCreateDTO> {}
