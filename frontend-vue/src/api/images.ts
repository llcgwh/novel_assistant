import { request, withNovelId, getApiBaseUrl, getCurrentNovelId } from './request'
import type { ID } from '@/types'

export interface UploadedImage {
  id: ID
  filename: string
  imageType: string
}

export const imagesApi = {
  getAll(): Promise<UploadedImage[]> {
    return request.get(withNovelId('/images'))
  },

  getById(id: ID): Promise<UploadedImage> {
    return request.get(withNovelId(`/images/${id}`))
  },

  getFileUrl(id: ID): string {
    return `${getApiBaseUrl()}/novels/${getCurrentNovelId()}/images/${id}/file`
  },

  async upload(file: File, imageType: string): Promise<UploadedImage> {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('imageType', imageType)

    return request.post(withNovelId('/images'), formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/images/${id}`))
  }
}
