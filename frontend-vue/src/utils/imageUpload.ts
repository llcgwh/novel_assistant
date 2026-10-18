export const MAX_IMAGE_BYTES = 8 * 1024 * 1024
export const MAX_BACKGROUND_BYTES = 2 * 1024 * 1024
export const IMAGE_FORMATS = 'PNG、JPEG、GIF、WebP 或 BMP'
export const IMAGE_ACCEPT = '.png,.jpg,.jpeg,.gif,.webp,.bmp,image/png,image/jpeg,image/gif,image/webp,image/bmp'

const mimeByExtension: Record<string, string> = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp',
}
type ImageFile = Pick<File, 'name' | 'size' | 'type'>

export function imageSizeLimit(maxBytes = MAX_IMAGE_BYTES): string {
  return `${maxBytes / 1024 / 1024} MiB`
}

export function getImageMimeType(file: ImageFile): string {
  const extension = file.name.split('.').pop()?.toLowerCase() || ''
  const mime = file.type.toLowerCase() || mimeByExtension[extension] || ''
  return mime === 'image/x-ms-bmp' ? 'image/bmp' : mime
}

export function validateImageFile(file: ImageFile, maxBytes = MAX_IMAGE_BYTES): void {
  if (!Number.isFinite(file.size) || file.size <= 0) throw new Error('图片文件为空，请重新选择')
  if (file.size > maxBytes) throw new Error(`图片超过 ${imageSizeLimit(maxBytes)} 上限，请压缩或选择较小的图片`)
  const extension = file.name.split('.').pop()?.toLowerCase() || ''
  if (!file.name.includes('.') || !mimeByExtension[extension] || mimeByExtension[extension] !== getImageMimeType(file)) {
    throw new Error(`请选择 ${IMAGE_FORMATS} 图片，文件格式须与扩展名一致`)
  }
}

export function getImageUploadError(error: unknown, maxBytes = MAX_IMAGE_BYTES): string {
  const detail = error as { name?: string; code?: string; message?: string; response?: { status?: number; data?: { message?: unknown; code?: unknown } } } | null
  const status = detail?.response?.status
  const message = detail?.response?.data?.message
  if (typeof message === 'string' && message.trim() && message.length <= 500) return message
  if (status === 413) return `图片超过服务器允许的大小，单张最多 ${imageSizeLimit(maxBytes)}。请压缩后重试`
  if (status === 415 || status === 400) return `图片格式无法读取，请选择有效的 ${IMAGE_FORMATS} 图片`
  if (status === 404) return '目标作品不存在或已移除，请重新进入作品后上传'
  if (detail?.code === 'ERR_CANCELED') return '作品已切换，本次上传结果未应用'
  if (detail?.code === 'ECONNABORTED' || detail?.code === 'ETIMEDOUT') return '图片上传超时，请检查连接后重试'
  if (detail?.code === 'ERR_NETWORK') return '无法连接图片服务，请检查连接后重试'
  if (detail?.name === 'QuotaExceededError') return '本浏览器存储空间不足，请释放空间后重试'
  if (status) return '图片上传失败，请稍后重试；原图片已保留'
  if (error instanceof Error && error.message && !/^Request failed with status code/.test(error.message)) return error.message
  return '图片上传失败，请重试；原图片已保留'
}
