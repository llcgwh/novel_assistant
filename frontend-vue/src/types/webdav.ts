export interface WebDavConfig {
  serverUrl: string
  username: string
  password: string
  autoSync: boolean
}

export interface WebDavStatus {
  serverUrl: string
  username: string
  hasPassword: boolean
  autoSync: boolean
  lastSyncTime: string | null
  configured: boolean
}

export interface ConnectionTestResult {
  success: boolean
  message: string
}

export interface SyncResult {
  success: boolean
  message: string
  timestamp?: string
  filename?: string
  size?: number
}

export interface RemoteFile {
  name: string
  path: string
  size: number
  modified: string | null
}
