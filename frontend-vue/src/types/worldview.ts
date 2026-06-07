import type { ID, Timestamp } from './index'
import type { Tag } from './tag'
import type { Character } from './character'
import type { Scene } from './scene'
import type { MapLocation } from './map'

export type WorldviewCategory =
  | 'geography'
  | 'history'
  | 'culture'
  | 'magic_tech'
  | 'races'
  | 'politics'
  | 'religion'
  | 'other'

export const WORLDVIEW_CATEGORIES: { value: WorldviewCategory; label: string; icon: string }[] = [
  { value: 'geography', label: '地理', icon: '🌍' },
  { value: 'history', label: '历史', icon: '📜' },
  { value: 'culture', label: '文化', icon: '🎭' },
  { value: 'magic_tech', label: '魔法/科技', icon: '⚡' },
  { value: 'races', label: '种族', icon: '👥' },
  { value: 'politics', label: '政治', icon: '🏛️' },
  { value: 'religion', label: '宗教', icon: '⛪' },
  { value: 'other', label: '其他', icon: '📝' }
]

export function getCategoryLabel(category: WorldviewCategory): string {
  const cat = WORLDVIEW_CATEGORIES.find(c => c.value === category)
  return cat ? cat.label : category
}

export function getCategoryIcon(category: WorldviewCategory): string {
  const cat = WORLDVIEW_CATEGORIES.find(c => c.value === category)
  return cat ? cat.icon : '📝'
}

export interface WorldviewEntry {
  id: ID
  name: string
  category: WorldviewCategory
  content?: string
  entryImage?: string
  tags?: Tag[]
  characters?: Character[]
  scenes?: Scene[]
  mapLocations?: MapLocation[]
  createdAt?: Timestamp
  updatedAt?: Timestamp
}

export interface WorldviewEntryCreateDTO {
  name: string
  category: WorldviewCategory
  content?: string
  entryImage?: string
}

export interface WorldviewEntryUpdateDTO extends Partial<WorldviewEntryCreateDTO> {}
