import { request } from './request'
import type { Novel, NovelCreateDTO, NovelUpdateDTO } from '@/types/novel'
import type { ID } from '@/types'

export const novelsApi = {
  getAll(): Promise<Novel[]> {
    return request.get('/novels')
  },

  getById(id: ID): Promise<Novel> {
    return request.get(`/novels/${id}`)
  },

  create(data: NovelCreateDTO): Promise<Novel> {
    return request.post('/novels', data)
  },

  update(id: ID, data: NovelUpdateDTO): Promise<Novel> {
    return request.put(`/novels/${id}`, data)
  },

  delete(id: ID): Promise<void> {
    return request.delete(`/novels/${id}`)
  },

  search(title: string): Promise<Novel[]> {
    return request.get('/novels/search', { params: { title } })
  },

  getByStatus(status: string): Promise<Novel[]> {
    return request.get(`/novels/status/${status}`)
  }
}
