import axios, { type AxiosInstance, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'

// 当前小说 ID
let currentNovelId: number | null = null
let contextVersion = 0
const requestContexts = new WeakMap<InternalAxiosRequestConfig, number>()

export function getNovelContextVersion(): number {
  return contextVersion
}

export function parseNovelId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return null
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : null
}

// 创建 axios 实例
const request: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 在调用时记录上下文；A → B → A 也不能接收第一次 A 的迟到响应。
request.interceptors.request.use((config) => {
  if (/^\/novels\/\d+\//.test(config.url || '')) {
    requestContexts.set(config, contextVersion)
  }
  return config
}, undefined, { synchronous: true })

function rejectStaleResponse(config?: InternalAxiosRequestConfig) {
  if (config && requestContexts.has(config) && requestContexts.get(config) !== contextVersion) {
    throw new axios.CanceledError('小说已切换，忽略旧请求结果')
  }
}

// 响应拦截器
request.interceptors.response.use(
  (response: AxiosResponse) => {
    rejectStaleResponse(response.config)
    return response.data
  },
  (error) => {
    rejectStaleResponse(error.config)
    if (!axios.isCancel(error)) console.error('API request failed:', error)
    return Promise.reject(error)
  }
)

// 设置当前小说 ID
export function setCurrentNovelId(id: number | null): void {
  if (id !== null && (!Number.isSafeInteger(id) || id <= 0)) {
    throw new Error('Invalid novel ID')
  }
  if (currentNovelId !== id) contextVersion++
  currentNovelId = id
  if (id) {
    localStorage.setItem('currentNovelId', String(id))
  } else {
    localStorage.removeItem('currentNovelId')
  }
}

// 获取当前小说 ID
export function getCurrentNovelId(): number {
  if (currentNovelId === null) {
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
