import { request, getApiBaseUrl } from './request'

export const backupApi = {
  download(novelId: number): void {
    window.open(`${getApiBaseUrl()}/novels/${novelId}/backup`, '_blank')
  },
  restore(novelId: number, file: File): Promise<{ message: string }> {
    return request.post(`/novels/${novelId}/backup`, file, {
      headers: { 'Content-Type': 'application/json' }, timeout: 120000
    })
  }
}
