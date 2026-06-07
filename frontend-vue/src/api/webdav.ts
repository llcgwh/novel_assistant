import { request, withNovelId } from './request'
import type { WebDavConfig, WebDavStatus, ConnectionTestResult, SyncResult, RemoteFile } from '@/types/webdav'

// Longer timeout for WebDAV operations (export + network round trip)
const SYNC_TIMEOUT = 120000 // 2 minutes

export const webdavApi = {
  testConnection(config: { serverUrl: string; username: string; password: string }): Promise<ConnectionTestResult> {
    return request.post(withNovelId('/webdav/test'), config, { timeout: 30000 })
  },

  getStatus(): Promise<WebDavStatus> {
    return request.get(withNovelId('/webdav/status'))
  },

  saveConfig(config: Partial<WebDavConfig>): Promise<{ status: string }> {
    return request.post(withNovelId('/webdav/config'), config)
  },

  syncUpload(): Promise<SyncResult> {
    return request.post(withNovelId('/webdav/sync/upload'), null, { timeout: SYNC_TIMEOUT })
  },

  syncDownload(filename: string): Promise<SyncResult> {
    return request.post(withNovelId('/webdav/sync/download'), { filename }, { timeout: SYNC_TIMEOUT })
  },

  getRemoteFiles(): Promise<RemoteFile[]> {
    return request.get(withNovelId('/webdav/files'), { timeout: 30000 })
  },

  deleteRemoteFile(filename: string): Promise<{ success: boolean; message: string }> {
    return request.delete(withNovelId('/webdav/files'), { data: { filename }, timeout: 30000 })
  }
}
