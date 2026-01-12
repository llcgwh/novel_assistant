import type { ID, Timestamp } from './index'
import type { Tag } from './tag'

export interface Scene {
  id: ID
  name: string
  location?: string
  description?: string
  atmosphere?: string
  sceneImage?: ID
  tags?: Tag[]
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface SceneCreateDTO {
  name: string
  location?: string
  description?: string
  atmosphere?: string
  sceneImage?: ID
}

export interface SceneUpdateDTO extends Partial<SceneCreateDTO> {}
