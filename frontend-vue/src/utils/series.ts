import type { PayloadField, SeriesKind, SeriesPayload, SeriesRevision } from '@/types/series'
export type { PayloadField } from '@/types/series'

export const SERIES_KINDS: ReadonlyArray<{ value: SeriesKind; label: string }> = [
  { value: 'calendar', label: '历法' },
  { value: 'location', label: '地点设定' },
  { value: 'race', label: '种族' },
  { value: 'organization', label: '组织' },
  { value: 'character', label: '人物模板' },
]

const commonFields: PayloadField[] = [
  { key: 'name', label: '名称', maxLength: 200 },
  { key: 'description', label: '描述', maxLength: 20000 },
  { key: 'notes', label: '备注', maxLength: 20000 },
]

const kindFields: Record<SeriesKind, ReadonlyArray<readonly [string, string]>> = {
  calendar: [['epoch', '纪元'], ['units', '时间单位'], ['dateFormat', '日期写法'], ['rules', '历法规则']],
  location: [['geography', '地理'], ['environment', '环境'], ['culture', '地方文化']],
  race: [['traits', '特征'], ['origins', '起源'], ['culture', '文化']],
  organization: [['purpose', '宗旨'], ['structure', '组织结构'], ['rules', '规则']],
  character: [['role', '角色定位'], ['personality', '性格'], ['appearance', '外貌'], ['background', '背景']],
}

export function seriesFields(kind: SeriesKind): PayloadField[] {
  if (!Object.prototype.hasOwnProperty.call(kindFields, kind)) throw Error('未知设定类型')
  return [
    ...commonFields.map(field => ({ ...field })),
    ...kindFields[kind].map(([key, label]) => ({ key, label, maxLength: 20000 })),
  ]
}

/** Produces only the exact whitelist, without inventing author content. */
export function emptySeriesPayload(kind: SeriesKind): SeriesPayload {
  return Object.fromEntries(seriesFields(kind).map(field => [field.key, '']))
}

/** Returns the first error, or an empty string. Validation never changes the draft. */
export function validateSeriesPayload(kind: SeriesKind, payload: unknown): string {
  if (!Object.prototype.hasOwnProperty.call(kindFields, kind)) return '未知设定类型'
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return '设定内容必须是完整的文本字段'
  const fields = seriesFields(kind)
  const allowed = new Set(fields.map(field => field.key))
  if (Reflect.ownKeys(payload).some(key => typeof key !== 'string' || !allowed.has(key))) {
    return '设定内容包含当前类型不支持的字段'
  }
  const value = payload as Record<string, unknown>
  for (const field of fields) {
    if (!Object.prototype.hasOwnProperty.call(value, field.key)) return `缺少${field.label}字段`
    const text = value[field.key]
    if (typeof text !== 'string') return `${field.label}必须是文本，空内容请使用空字符串`
    if (field.key === 'name' && !text.trim()) return '请填写名称'
    if ((field.key === 'name' ? text.trim() : text).length > field.maxLength) {
      return `${field.label}不能超过 ${field.maxLength} 字`
    }
  }
  // Names are trimmed by the server; all other content is serialized exactly as entered.
  const serialized = JSON.stringify({ ...value, name: (value.name as string).trim() })
  if (new TextEncoder().encode(serialized).byteLength > 256 * 1024) return '设定内容不能超过 256 KiB，请缩短文本后重试'
  return ''
}

export function seriesKindLabel(kind: SeriesKind): string {
  return SERIES_KINDS.find(item => item.value === kind)?.label || kind
}

export function seriesFieldLabel(kind: SeriesKind, key: string): string {
  return seriesFields(kind).find(field => field.key === key)?.label || key
}

/** Series transport data is JSON; this also accepts Vue reactive draft objects. */
export function cloneSeries<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

/** A display counter alone cannot distinguish independently published branches. */
export function revisionLabel(revision: Pick<SeriesRevision, 'number' | 'uid'>): string {
  return `v${revision.number} · ${revision.uid.slice(0, 8)}`
}
