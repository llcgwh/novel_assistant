import { request } from './request'

export const backupApi = {
  download(novelId: number): Promise<Blob> {
    return request.get(`/novels/${novelId}/backup`, { responseType: 'blob', timeout: 120000 })
  },
  restore(novelId: number, file: File): Promise<{ message: string; copyNovelId?: number }> {
    return request.post(`/novels/${novelId}/backup`, file, {
      headers: { 'Content-Type': 'application/json' }, timeout: 120000
    })
  }
}
