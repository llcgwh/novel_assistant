export interface DocNode {
  type: string
  text?: string
  attrs?: Record<string, any>
  marks?: { type: string }[]
  content?: DocNode[]
}
export interface WritingLink {
  uid: string
  type: string
  targetId: number | null
  title: string
  blockId?: string
  excerpt?: string
  role: string
  plannedRole?: string
  missing?: boolean
}
export interface ChapterMeta {
  uid: string
  volumeId: string | null
  position: number
  title: string
  summary: string
  status: string
  goal: number
  numbered: boolean
  deleted: boolean
  revision: number
  wordCount: number
  updatedAt: string
  links: WritingLink[]
}
export interface Chapter extends ChapterMeta {
  doc: DocNode
  notes: string
  mutationId?: string
}
export interface Volume {
  uid: string
  title: string
}
export interface SessionRecord {
  uid: string
  sequence: number
  date: string
  chapterUid: string
  typed: number
  pasted: number
  net: number
  activeSeconds: number
  peak: number
}
export interface WritingDate {
  date: string
  timezoneOffsetMinutes: number
}
export interface WritingStatsDay {
  date: string
  goal: number | null
  revisionSaves: number
  finalTransitions: number
  completedChapterUids: string[]
  focusSeconds: number
  focusCompleted: number
}
export interface WritingStats {
  schemaVersion: 1
  version: number
  days: WritingStatsDay[]
}
export interface FocusReceipt {
  uid: string
  endedOn: string
  completed: boolean
  secondsByDate: Record<string, number>
  timezoneOffsetMinutes: number
}
export interface WritingWorkspace {
  uid: string
  structureVersion: number
  volumes: Volume[]
  chapters: ChapterMeta[]
  preferences: Record<string, any>
  sessions: SessionRecord[]
  stats?: WritingStats
  changeSequence: number
  syncedSequence: number
  syncMessage?: string
  lastSync?: string
}
export interface DraftRecord {
  key: string
  bookUid: string
  novelId: number
  chapter: Chapter
  updatedAt: number
  tabId: string
}
export interface Resource {
  id: number
  type: string
  name: string
  detail: string
  image?: string
  aliases?: string[]
  raw: any
}
