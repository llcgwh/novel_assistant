export interface AppearanceSettings { backgroundImage: string; backgroundOpacity: number }
export const defaultAppearance: AppearanceSettings = { backgroundImage: '', backgroundOpacity: 0.55 }
const rasterData = /^data:image\/(png|jpeg|gif|webp|bmp);base64,[A-Za-z0-9+/=\s]+$/
export function safeImage(value: unknown): string {
  if (typeof value !== 'string' || value.length > 3 * 1024 * 1024) throw new Error('背景图片过大或格式无效')
  if (!value || rasterData.test(value) || /^https?:\/\//i.test(value) || /^\/api\/novels\/\d+\/images\/\d+\/file$/.test(value)) return value
  throw new Error('不支持的背景图片地址')
}
export function parseAppearance(value: unknown): AppearanceSettings {
  const data = value as Partial<AppearanceSettings> | null
  if (!data || typeof data !== 'object' || typeof data.backgroundOpacity !== 'number' || !Number.isFinite(data.backgroundOpacity) || data.backgroundOpacity < 0 || data.backgroundOpacity > 1) throw new Error('透明度必须在 0 到 1 之间')
  return { backgroundImage: safeImage(data.backgroundImage), backgroundOpacity: data.backgroundOpacity }
}
export function parseAppearanceBackup(value: unknown) {
  const data = value as { format?: unknown; settings?: unknown; mapBackground?: unknown }
  if (data?.format !== 'novel-appearance-v1') throw new Error('不支持的外观备份格式')
  const map = data.mapBackground
  if (map !== undefined && map !== 'auto' && map !== 'none' && !(typeof map === 'string' && rasterData.test(map) && map.length <= 12 * 1024 * 1024)) throw new Error('地图背景数据无效')
  return { settings: parseAppearance(data.settings), mapBackground: map as string | undefined }
}
