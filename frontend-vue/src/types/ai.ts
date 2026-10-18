import type { Chapter } from './writing'

export type AiScope = 'character' | 'location' | 'scene' | 'worldview' | 'outline' | 'timeline' | 'foreshadow' | 'relationship' | 'writing'
export type AiAction = 'character.name' | 'character.personality' | 'character.background' | 'character.appearance'
  | 'location.name' | 'location.description' | 'scene.name' | 'scene.description' | 'worldview.content'
  | 'outline.review' | 'timeline.review' | 'foreshadow.review' | 'relationship.review' | 'writing.inspiration'
export interface AiConfig {
  baseUrl: string
  model: string
  endpointMode: 'remote' | 'local'
  proxyUrl: string
  hasKey: boolean
  configured: boolean
  revision: string
  maxInputChars: number
  maxOutputTokens: number
  tokenParameter: 'max_tokens' | 'max_completion_tokens'
}
export interface AiConfigInput {
  baseUrl: string
  model: string
  endpointMode: 'remote' | 'local'
  proxyUrl?: string
  apiKey?: string
  clearKey?: boolean
  expectedRevision: string
  maxInputChars?: number
  maxOutputTokens?: number
  tokenParameter?: 'max_tokens' | 'max_completion_tokens'
}
export interface AiContext {
  targetId?: number
  targetField?: string
  targetDraft?: Record<string, unknown>
  chapterUid?: string
  currentDraft?: Chapter
  request?: string
  referenceChapterUids?: string[]
}
export interface AiReferenceChapter { uid: string; title: string; wordCount: number }
export interface AiReferenceChapters { maxChars: number; chapters: AiReferenceChapter[] }
export interface AiCandidate {
  title: string
  reason: string
  fields: Record<string, string>
  sourceRefs: string[]
  assumptions: string[]
  conflicts: string[]
}
export interface AiDirection {
  title: string
  goal: string
  choice: string
  consequence: string
  risk: string
  question: string
  sourceRefs: string[]
  assumptions: string[]
}
export interface AiIssue { title: string; detail: string; sourceRefs: string[] }
export interface AiSource { id: string; type: string; title: string; revision?: number; hash: string }
export interface AiResponse {
  snapshotId: string
  requestId: string
  action: AiAction
  result: { candidates: AiCandidate[]; directions: AiDirection[]; issues: AiIssue[] }
  coverage: { mode: 'settings' | 'full'; settingsCount: number; chaptersBefore: number; currentChapter: string | number; chaptersAfter: number; includesUnsavedDraft: boolean; sourceCount: number; selectedChapterCount?: number; selectedManuscriptChars?: number }
  sources: AiSource[]
  model?: string
  attemptCount?: number
  correctionApplied?: boolean
  correctionReason?: string
  reviewMode?: 'writing-boundary' | 'structure-only'
  usage?: { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number; requestCount?: number; incomplete?: boolean }
}
