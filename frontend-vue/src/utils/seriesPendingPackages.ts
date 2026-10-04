import type { SeriesPack } from '@/types/series'

export interface SeriesPackageStore {
  put(id: string, value: SeriesPack): Promise<void>
  get(id: string): Promise<SeriesPack | undefined>
  remove(id: string): Promise<void>
}

// A complete library can exceed localStorage's quota. Keep its exact submitted
// package separately; the small mutation receipt still survives in localStorage.
const databaseName = 'novel-assistant-series-pending'
async function database(): Promise<IDBDatabase> {
  if (!globalThis.indexedDB) throw Error('浏览器无法暂存完整母本包，请启用本机存储后重试；尚未导入。')
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1)
    request.onupgradeneeded = () => request.result.createObjectStore('packages')
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || Error('母本包暂存失败'))
    request.onblocked = () => reject(Error('母本包暂存被另一页面阻塞，请关闭旧页面后重试。'))
  })
}
async function transaction<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await database()
  try {
    return await new Promise<T>((resolve, reject) => {
      const tx = db.transaction('packages', mode)
      const request = operation(tx.objectStore('packages'))
      tx.oncomplete = () => resolve(request.result)
      tx.onerror = () => reject(tx.error || request.error || Error('母本包暂存失败'))
      tx.onabort = () => reject(tx.error || Error('母本包暂存未完成'))
    })
  } finally { db.close() }
}

export const seriesPackageStore: SeriesPackageStore = {
  async put(id, value) { await transaction('readwrite', store => store.put(value, id)) },
  get(id) { return transaction('readonly', store => store.get(id)) },
  async remove(id) { await transaction('readwrite', store => store.delete(id)) },
}

export async function seriesPackageDigest(value: SeriesPack): Promise<string> {
  const bytes = new TextEncoder().encode(JSON.stringify(value))
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}
