export interface StorySource {
  chapterUid: string
  blockId: string
  excerpt: string
  eventId: number | null
  eventTitle: string
  note: string
}
export interface StoryRecord {
  uid: string
  storyOrder: number | null
  narrativeOrder: number | null
  source: StorySource
  recordedAt: string
  retracted: boolean
}
export interface CharacterState extends StoryRecord {
  characterId: number | null
  characterName: string
  field: string
  value: string
}
export type KnowledgeState = 'know' | 'believe' | 'suspect' | 'unknown'
export interface KnowledgeRecord extends StoryRecord {
  characterId: number | null
  characterName: string
  state: KnowledgeState
  belief: string
}
export interface ReaderRecord extends StoryRecord {
  state: 'hidden' | 'hinted' | 'revealed'
  detail: string
}
export interface StorySecret {
  uid: string
  title: string
  truth: string
  recordedAt: string
  archived: boolean
  knowledge: KnowledgeRecord[]
  reader: ReaderRecord[]
}
export interface StoryState {
  schemaVersion: 1
  states: CharacterState[]
  secrets: StorySecret[]
}
export type StoryDraftKind = 'state' | 'secret' | 'knowledge' | 'reader'
export interface StoryDraft {
  version: 1
  novelId: number
  kind: StoryDraftKind
  uid: string
  recordedAt: string
  secretUid: string
  characterId: string
  field: string
  value: string
  title: string
  truth: string
  belief: string
  detail: string
  knowledgeState: KnowledgeState
  readerState: ReaderRecord['state']
  storyOrder: string | number
  narrativeOrder: string | number
  source: StorySource
}
