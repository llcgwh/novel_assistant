// 通用 ID 类型
export type ID = number

// 通用时间戳类型
export type Timestamp = string

// 分页参数
export interface PaginationParams {
  page?: number
  size?: number
}

// 分页响应
export interface PaginatedResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

// 搜索结果
export interface SearchResult {
  characters: import('./character').Character[]
  scenes: import('./scene').Scene[]
  foreshadows: import('./foreshadow').Foreshadow[]
  outlines: import('./outline').Outline[]
  timelineEvents: import('./timeline').TimelineEvent[]
  mapLocations: import('./map').MapLocation[]
  tags: import('./tag').Tag[]
}

// 导出所有类型
export * from './novel'
export * from './character'
export * from './scene'
export * from './foreshadow'
export * from './outline'
export * from './timeline'
export * from './map'
export * from './relationship'
export * from './tag'
