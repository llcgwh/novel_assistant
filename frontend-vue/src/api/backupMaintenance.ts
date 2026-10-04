import { request } from './request'
import type { BackupOperationsPage, BackupStorage, BackupRetention, CleanupPreview, CleanupResult, CleanupSubmission } from '@/types/backupMaintenance'
const url = (novelId: number, path: string) => `/novels/${novelId}/webdav/${path}`
export const backupMaintenanceApi = {
  operations(novelId: number, before?: number): Promise<BackupOperationsPage> {
    return request.get(url(novelId, 'operations'), { params: { limit: 30, ...(before === undefined ? {} : { before }) } })
  },
  storage(novelId: number): Promise<BackupStorage> {
    return request.get(url(novelId, 'storage'), { timeout: 60000 })
  },
  retention(novelId: number): Promise<BackupRetention> { return request.get(url(novelId, 'retention')) },
  saveRetention(novelId: number, policy: Pick<BackupRetention, 'keepLast' | 'keepDays' | 'version'>): Promise<BackupRetention> {
    return request.put(url(novelId, 'retention'), policy)
  },
  preview(novelId: number): Promise<CleanupPreview> {
    return request.post(url(novelId, 'cleanup/preview'), {}, { timeout: 120000 })
  },
  execute(novelId: number, submission: CleanupSubmission): Promise<CleanupResult> {
    return request.post(url(novelId, 'cleanup/execute'), submission, { timeout: 120000 })
  },
  cancel(novelId: number, token: string): Promise<unknown> {
    return request.delete(url(novelId, `cleanup/${encodeURIComponent(token)}`))
  },
}
