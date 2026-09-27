import { request } from './request'
import type { WritingDesk } from '@/types/writingDesk'
const path = (id: number) => `/novels/${id}/writing/desk`
export const writingDeskApi = {
  get: (id: number) => request.get<any, WritingDesk>(path(id)),
  save: (id: number, value: WritingDesk) =>
    request.put<any, WritingDesk>(path(id), value),
}
