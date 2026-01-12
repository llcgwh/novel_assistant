import axios, { type AxiosInstance, type AxiosResponse } from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'

// 当前小说 ID
let currentNovelId: number | null = null

// 创建 axios 实例
const request: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 响应拦截器
request.interceptors.response.use(
  (response: AxiosResponse) => response.data,
  (error) => {
    console.error('API request failed:', error)
    return Promise.reject(error)
  }
)

// 设置当前小说 ID
export function setCurrentNovelId(id: number | null): void {
  currentNovelId = id
  if (id) {
    localStorage.setItem('currentNovelId', String(id))
  } else {
    localStorage.removeItem('currentNovelId')
  }
}

// 获取当前小说 ID
export function getCurrentNovelId(): number {
  if (!currentNovelId) {
    const stored = localStorage.getItem('currentNovelId')
    if (stored) {
      currentNovelId = parseInt(stored)
    }
  }
  if (!currentNovelId) {
    throw new Error('No novel selected')
  }
  return currentNovelId
}

// 构建带小说 ID 的 URL
export function withNovelId(path: string): string {
  return `/novels/${getCurrentNovelId()}${path}`
}

// 获取 API 基础 URL
export function getApiBaseUrl(): string {
  return API_BASE_URL
}

export { request }
