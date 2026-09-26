import type { DraftRecord } from '@/types/writing'
let connection: Promise<IDBDatabase> | undefined
function database() {
  return (connection ??= new Promise((resolve, reject) => {
    const req = indexedDB.open('ink-manuscripts', 1)
    req.onupgradeneeded = () =>
      req.result.createObjectStore('drafts', { keyPath: 'key' })
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => {
      connection = undefined
      reject(req.error)
    }
  }))
}
export async function saveDraft(record: DraftRecord) {
  const db = await database()
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction('drafts', 'readwrite')
    tx.objectStore('drafts').put(record)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })
}
export async function deleteDraft(key: string) {
  const db = await database()
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction('drafts', 'readwrite')
    tx.objectStore('drafts').delete(key)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
}
export async function listDrafts(bookUid: string, novelId?: number) {
  const db = await database()
  return new Promise<DraftRecord[]>((resolve, reject) => {
    const req = db.transaction('drafts').objectStore('drafts').getAll()
    req.onsuccess = () =>
      resolve(
        (req.result as DraftRecord[])
          .filter(
            (d) =>
              d.bookUid === bookUid &&
              (novelId === undefined || d.novelId === novelId),
          )
          .sort((a, b) => b.updatedAt - a.updatedAt),
      )
    req.onerror = () => reject(req.error)
  })
}
