import { request, withNovelId, getApiBaseUrl, getCurrentNovelId } from './request'
import type { ID } from '@/types'
import { getImageMimeType, validateImageFile } from '@/utils/imageUpload'

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

  getByType(imageType: string): Promise<UploadedImage[]> {
    return request.get(withNovelId(`/images/type/${imageType}`))
  },

  getFileUrl(id: ID, novelId?: ID): string {
    return `${getApiBaseUrl()}/novels/${novelId ?? getCurrentNovelId()}/images/${id}/file`
  },

  async upload(file: File, imageType: string, novelId?: ID): Promise<UploadedImage> {
    validateImageFile(file)
    const formData = new FormData()
    // Some systems omit a MIME type even for a known raster extension.
    const mime = getImageMimeType(file)
    const image = file.type === mime ? file : new File([file], file.name, { type: mime, lastModified: file.lastModified })
    formData.append('file', image)
    formData.append('imageType', imageType)

    return request.post(novelId ? `/novels/${novelId}/images` : withNovelId('/images'), formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000,
    })
  },

  delete(id: ID): Promise<void> {
    return request.delete(withNovelId(`/images/${id}`))
  }
}
