export interface ChapterAnchor {
  chapterUid: string
  blockId: string
  excerpt: string
}
export interface NextPen extends ChapterAnchor {
  nextScene: string
  question: string
  opening: string
  updatedAt: string
}
export type RevisionCategory =
  | 'dialogue'
  | 'pov'
  | 'wording'
  | 'setting'
  | 'plot'
  | 'other'
export interface RevisionTask extends ChapterAnchor {
  uid: string
  roundUid?: string
  body: string
  category: RevisionCategory
  priority: 'normal' | 'high'
  status: 'open' | 'done'
  createdAt: string
  updatedAt: string
}
export interface RevisionRound {
  uid: string
  title: string
  goal: string
  status: 'active' | 'archived'
  createdAt: string
  updatedAt: string
}
export interface ReaderBookmark extends ChapterAnchor {
  uid: string
  label: string
  createdAt: string
}
export interface Idea {
  uid: string
  title: string
  body: string
  category: 'plot' | 'character' | 'scene' | 'setting' | 'dialogue' | 'other'
  chapterUids: string[]
  createdAt: string
  updatedAt: string
  sourceKey?: string
}
export interface WritingDesk {
  version: number
  nextPen: NextPen | null
  tasks: RevisionTask[]
  rounds?: RevisionRound[]
  bookmarks: ReaderBookmark[]
  ideas?: Idea[]
  mutationId?: string
}
