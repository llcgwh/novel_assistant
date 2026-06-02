import { request, withNovelId } from './request'

export interface CharacterNetworkNode {
  id: number
  name: string
  role?: string
  portraitImage?: string
}

export interface CharacterNetworkEdge {
  id: number
  source: number
  target: number
  relationshipType?: string
  description?: string
}

export interface CharacterNetwork {
  nodes: CharacterNetworkNode[]
  edges: CharacterNetworkEdge[]
}

export const visualizationApi = {
  getCharacterNetwork(): Promise<CharacterNetwork> {
    return request.get(withNovelId('/visualization/character-network'))
  }
}
