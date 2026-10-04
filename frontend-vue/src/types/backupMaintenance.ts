export interface BackupOperation {
  id: number
  type: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'PARTIAL' | 'CANCELLED'
  startedAt: string
  finishedAt?: string | null
  stage?: string | null
  message?: string | null
  errorType?: string | null
  httpStatus?: number | null
  details?: unknown
}
export interface BackupOperationsPage { items: BackupOperation[]; nextBefore: number | null }
export interface BackupStorage {
  bookUid: string
  snapshotBytes: number
  snapshotCount: number
  legacyBytes: number
  legacyCount: number
  metadataBytes: number
  totalBytes: number
  unknownSizeCount: number
  quotaAvailableBytes: number | null
  quotaUsedBytes: number | null
}
export interface BackupRetention { keepLast: number; keepDays: number; version: number; automatic: false }
export interface CleanupFile {
  file: string
  revision?: string | null
  size: number
  time?: string | null
  etag?: string | null
  reason: string
}
export interface CleanupPreview {
  token: string
  expiresAt: string
  bookUid: string
  policy: BackupRetention
  candidates: CleanupFile[]
  retained: CleanupFile[]
  protectedCount: number
  candidateBytes: number
  retainedBytes: number
  legacyProtectedCount: number
  legacyProtectedBytes: number
  bridgeMarkers: string[]
  message: string
}
export interface CleanupResult {
  token: string
  requestId: string
  status: 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'RUNNING'
  deleted: string[]
  deletedBytes: number
  uncertainFiles?: string[]
  unlockWarning?: string
  connectionWarning?: string
  message: string
  operationId: number
}
export interface CleanupSubmission { token: string; requestId: string; confirmed: true }
