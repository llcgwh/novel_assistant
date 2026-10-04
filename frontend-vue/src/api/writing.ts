import { request } from './request'
import type { Chapter, WritingWorkspace, SessionRecord, WritingStats, WritingDate, FocusReceipt } from '@/types/writing'
const url = (id: number, path = '') => `/novels/${id}/writing${path}`
export const writingApi = {
  workspace: (id: number) => request.get<any, WritingWorkspace>(url(id)),
  chapter: (id: number, uid: string) =>
    request.get<any, Chapter>(url(id, `/chapters/${uid}`)),
  create: (id: number, body: Partial<Chapter>) =>
    request.post<any, Chapter>(url(id, '/chapters'), body),
  save: (id: number, body: Chapter & Partial<WritingDate> & { checkpoint?: boolean }) =>
    request.put<any, Chapter>(url(id, `/chapters/${body.uid}`), body),
  structure: (id: number, body: unknown) =>
    request.put<any, WritingWorkspace>(url(id, '/structure'), body),
  preferences: (id: number, body: unknown) =>
    request.put<any, WritingWorkspace>(url(id, '/preferences'), body),
  split: (id: number, uid: string, body: unknown) =>
    request.post<any, Chapter>(url(id, `/chapters/${uid}/split`), body),
  merge: (id: number, uid: string, body: unknown) =>
    request.post<any, Chapter>(url(id, `/chapters/${uid}/merge`), body),
  revisions: (id: number, uid: string) =>
    request.get<
      any,
      { id: number; revision: number; label: string; createdAt: string }[]
    >(url(id, `/chapters/${uid}/revisions`)),
  revision: (id: number, uid: string, r: number) =>
    request.get<any, Chapter>(url(id, `/chapters/${uid}/revisions/${r}`)),
  session: (id: number, record: SessionRecord) =>
    request.put(url(id, `/sessions/${record.uid}`), record),
  stats: (id: number) => request.get<any, WritingStats>(url(id, '/stats')),
  statsDay: (id: number, body: WritingDate) =>
    request.post<any, WritingStats>(url(id, '/stats/day'), body),
  focus: (id: number, body: FocusReceipt) =>
    request.post<any, WritingStats>(url(id, `/stats/focus/${body.uid}`), body),
  search: (id: number, q: string) =>
    request.get<
      any,
      { uid: string; title: string; excerpt: string; wordCount: number }[]
    >(url(id, '/search'), { params: { q } }),
  backlinks: (id: number, type: string, target: number) =>
    request.get<any, any[]>(url(id, '/backlinks'), {
      params: { type, target },
    }),
  export: (id: number, options: unknown) =>
    request.post<any, Blob>(url(id, '/export'), options, {
      responseType: 'blob',
      timeout: 60000,
    }),
}
