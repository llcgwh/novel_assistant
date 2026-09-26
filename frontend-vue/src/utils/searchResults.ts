import type { SearchResult } from '@/types'

export const SEARCH_CATEGORIES = [
  { key: 'characters', label: '人物', path: 'characters', icon: '人' },
  { key: 'scenes', label: '场景', path: 'scenes', icon: '景' },
  { key: 'foreshadows', label: '伏笔', path: 'foreshadows', icon: '线' },
  { key: 'outlines', label: '大纲', path: 'outlines', icon: '纲' },
  { key: 'timelineEvents', label: '时间轴', path: 'timeline', icon: '时' },
  { key: 'mapLocations', label: '地图', path: 'map', icon: '图' },
  { key: 'worldviewEntries', label: '世界观', path: 'worldview', icon: '世' }
] as const

export type SearchCategory = typeof SEARCH_CATEGORIES[number]['key']

export function emptySearchResults(): SearchResult {
  return { characters: [], scenes: [], foreshadows: [], outlines: [], timelineEvents: [], mapLocations: [], worldviewEntries: [], tags: [] }
}

export function excerpt(text: string, keyword: string, limit = 130): string {
  if (text.length <= limit) return text
  const match = keyword ? text.toLocaleLowerCase().indexOf(keyword.toLocaleLowerCase()) : -1
  const start = Math.max(0, match - 35)
  return `${start ? '…' : ''}${text.slice(start, start + Math.max(limit, keyword.length))}${start + Math.max(limit, keyword.length) < text.length ? '…' : ''}`
}

// Return text segments instead of HTML, so names and snippets are always escaped by Vue.
export function highlightParts(text: string, keyword: string) {
  const parts: { text: string; match: boolean }[] = []
  if (!keyword) return [{ text, match: false }]
  const lower = text.toLocaleLowerCase()
  const needle = keyword.toLocaleLowerCase()
  let cursor = 0
  let index = lower.indexOf(needle)
  while (index !== -1) {
    if (index > cursor) parts.push({ text: text.slice(cursor, index), match: false })
    parts.push({ text: text.slice(index, index + keyword.length), match: true })
    cursor = index + keyword.length
    index = lower.indexOf(needle, cursor)
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor), match: false })
  return parts
}

export function searchGroups(results: SearchResult, keyword: string) {
  return SEARCH_CATEGORIES.map(category => ({
    ...category,
    items: (results[category.key] || []).map(item => {
      const details = ['role', 'location', 'content', 'description', 'personality', 'appearance', 'background', 'locationType', 'eventTime']
        .map(field => field in item ? (item as unknown as Record<string, unknown>)[field] : null)
        .filter((value): value is string => typeof value === 'string' && value.length > 0)
      const description = details.find(value => value.toLocaleLowerCase().includes(keyword.toLocaleLowerCase())) || details[0] || ''
      return { id: item.id, title: 'name' in item ? item.name : item.title, excerpt: excerpt(description, keyword) }
    })
  }))
}
