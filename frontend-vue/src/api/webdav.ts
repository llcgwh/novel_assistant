import { request } from './request'
import type { WebDavConfig, WebDavStatus, ConnectionTestResult, SyncResult, RemoteFile } from '@/types/webdav'

// Longer timeout for WebDAV operations (export + network round trip)
const SYNC_TIMEOUT = 120000 // 2 minutes

export const webdavApi = {
  testConnection(novelId: number, config: { serverUrl: string; username: string; password: string }): Promise<ConnectionTestResult> {
    return request.post(`/novels/${novelId}/webdav/test`, config, { timeout: 30000 })
  },

  getStatus(novelId: number): Promise<WebDavStatus> {
    return request.get(`/novels/${novelId}/webdav/status`)
  },

  saveConfig(novelId: number, config: Partial<WebDavConfig>): Promise<{ status: string }> {
    return request.post(`/novels/${novelId}/webdav/config`, config)
  },

  syncUpload(novelId: number): Promise<SyncResult> {
    return request.post(`/novels/${novelId}/webdav/sync/upload`, null, { timeout: SYNC_TIMEOUT })
  },

  syncDownload(novelId: number, filename: string): Promise<SyncResult> {
    return request.post(`/novels/${novelId}/webdav/sync/download`, { filename }, { timeout: SYNC_TIMEOUT })
  },

  getRemoteFiles(novelId: number): Promise<RemoteFile[]> {
    return request.get(`/novels/${novelId}/webdav/files`, { timeout: 30000 })
  },

  deleteRemoteFile(novelId: number, filename: string): Promise<{ success: boolean; message: string }> {
    return request.delete(`/novels/${novelId}/webdav/files`, { data: { filename }, timeout: 30000 })
  }
}
