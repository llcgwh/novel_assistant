export type SeriesKind = 'calendar' | 'location' | 'race' | 'organization' | 'character'
export type SeriesAuthorStatus = 'draft' | 'confirmed'

/** Every key in the kind's whitelist is required; optional text is an empty string. */
export type SeriesPayload = Record<string, string>

export interface PayloadField {
  key: string
  label: string
  maxLength: number
}

export interface SeriesRevision {
  uid: string
  templateUid: string
  parentRevisionUid: string | null
  number: number
  kind: SeriesKind
  seriesName: string
  payload: SeriesPayload
  authorStatus: SeriesAuthorStatus
  changeNote: string
  createdAt: string
  hash: string
}

export interface SeriesTemplate {
  uid: string
  kind: SeriesKind
  seriesName: string
  name: string
  headRevisionUid: string
  headRevisionNumber: number
  lockVersion: number
  archived: boolean
  revisionCount: number
  createdAt: string
  updatedAt: string
}

export interface SeriesWorld {
  uid: string
  name: string
  description: string
}

export interface SeriesOrigin {
  templateUid: string
  revisionUid: string
  copiedAt: string
}

export interface SeriesReview {
  revisionUid: string
  adoptedFields: string[]
  keptFields: string[]
  reviewedAt: string
  mode: 'copy' | 'adopt' | 'review'
}

export interface SeriesFieldOrigin {
  kind: 'source' | 'local'
  revisionUid: string | null
}

export interface SeriesCopySnapshot {
  universeUid: string
  planet: string
  content: SeriesPayload
  authorStatus: SeriesAuthorStatus
  baselineRevisionUid: string
  lastReview: SeriesReview
  fieldOrigins: Record<string, SeriesFieldOrigin>
  archived: boolean
}

export interface SeriesCopyHistory {
  uid: string
  createdAt: string
  action: 'edit' | 'adopt' | 'review' | 'restore' | 'archive'
  before: SeriesCopySnapshot
}

export interface SeriesCopy extends SeriesCopySnapshot {
  uid: string
  kind: SeriesKind
  origin: SeriesOrigin
  createdAt: string
  updatedAt: string
  history: SeriesCopyHistory[]
}

export interface SeriesState {
  schemaVersion: 1
  version: number
  epoch: string
  worlds: SeriesWorld[]
  copies: SeriesCopy[]
  sourceRevisions: SeriesRevision[]
}

export interface SeriesFieldDiff {
  key: string
  base: string
  local: string
  incoming: string
  sourceChanged: boolean
  localChanged: boolean
  conflict: boolean
  different: boolean
}

export interface SeriesComparison {
  copyUid: string
  epoch: string
  expectedVersion: number
  copyHash: string
  baselineRevisionUid: string
  revision: SeriesRevision
  fields: SeriesFieldDiff[]
}

export interface SeriesPack {
  format: 'novel-assistant-series-v1'
  schemaVersion: 1
  exportedAt: string
  templates: Array<{
    uid: string
    kind: SeriesKind
    headRevisionUid: string
    archived: boolean
  }>
  revisions: SeriesRevision[]
}

export interface SeriesImportPreview {
  planToken: string
  packageHash: string
  createTemplates: number
  addRevisions: number
  reuseRevisions: number
  preservedHeads: number
  heads: Array<{
    templateUid: string
    currentHeadRevisionUid: string
    incomingHeadRevisionUid: string
  }>
}

export interface SeriesMutationResult {
  /** Current state, including later edits when this is an exact replay. */
  state: SeriesState
  replayed: boolean
  /** The version originally committed by this mutation. */
  resultVersion: number
}

export interface SeriesTemplateResult {
  template: SeriesTemplate
  revision?: SeriesRevision
  replayed: boolean
}

export interface SeriesPublishedTemplateResult extends SeriesTemplateResult {
  revision: SeriesRevision
}

export interface SeriesImportResult {
  createdTemplates: number
  addedRevisions: number
  reusedRevisions: number
  preservedHeads: number
  replayed: boolean
}

export interface SeriesTemplateDetails {
  template: SeriesTemplate
  revisions: SeriesRevision[]
}

export interface SeriesMutationBody {
  mutationId: string
  epoch: string
  expectedVersion: number
}

export interface SeriesTemplateContent {
  seriesName: string
  payload: SeriesPayload
  authorStatus: SeriesAuthorStatus
  changeNote: string
}

export interface SeriesCreateTemplateBody extends SeriesTemplateContent {
  mutationId: string
  templateUid: string
  kind: SeriesKind
}

export interface SeriesPublishTemplateBody extends SeriesTemplateContent {
  mutationId: string
  expectedLockVersion: number
  expectedHeadRevisionUid: string
}

export interface SeriesSelectHeadBody {
  mutationId: string
  expectedLockVersion: number
  expectedHeadRevisionUid: string
  revisionUid: string
}

export interface SeriesArchiveTemplateBody {
  mutationId: string
  expectedLockVersion: number
  archived: boolean
}

export interface SeriesUpdateWorldBody extends SeriesMutationBody {
  name: string
  description: string
}

export interface SeriesCreateWorldBody extends SeriesUpdateWorldBody {
  worldUid: string
}

export interface SeriesCreateCopyBody extends SeriesMutationBody {
  copyUid: string
  templateUid: string
  revisionUid: string
  universeUid: string
  planet: string
}

export interface SeriesDuplicateCopyBody extends SeriesMutationBody {
  copyUid: string
  universeUid: string
  planet: string
}

export interface SeriesUpdateCopyBody extends SeriesMutationBody {
  content: SeriesPayload
  authorStatus: SeriesAuthorStatus
  planet: string
}

export interface SeriesArchiveCopyBody extends SeriesMutationBody {
  archived: boolean
}

export interface SeriesRestoreCopyBody extends SeriesMutationBody {
  historyUid: string
}

export interface SeriesReviewBody extends SeriesMutationBody {
  copyHash: string
  baselineRevisionUid: string
  revisionUid: string
}

export interface SeriesAdoptBody extends SeriesReviewBody {
  selectedFields: string[]
}

export interface SeriesImportBody {
  mutationId: string
  planToken: string
  package: SeriesPack
}
