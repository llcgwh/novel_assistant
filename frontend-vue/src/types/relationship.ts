import type { ID, Timestamp } from './index'

export type RelationshipType = '朋友' | '敌人' | '恋人' | '亲属' | '同事' | '师徒' | '其他'

export interface Relationship {
  id: ID
  characterId1: ID
  characterId2: ID
  character1?: { id: ID; name: string }
  character2?: { id: ID; name: string }
  relationshipType?: RelationshipType | string
  description?: string
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface RelationshipCreateDTO {
  characterId1: ID
  characterId2: ID
  relationshipType?: string
  description?: string
}

export interface RelationshipUpdateDTO extends Partial<RelationshipCreateDTO> {}

// ========== 关系组 ==========

export interface RelationshipGroup {
  id: ID
  novelId: ID
  name: string
  description?: string
  parentGroupId?: ID | null
  characters: { id: ID; name: string }[]
  createdAt?: Timestamp
}

export interface RelationshipGroupCreateDTO {
  name: string
  description?: string
  parentGroupId?: ID | null
  characterIds?: ID[]
}

export interface RelationshipGroupUpdateDTO extends Partial<RelationshipGroupCreateDTO> {}
