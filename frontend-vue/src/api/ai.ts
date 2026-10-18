import { getApiBaseUrl, getNovelContextVersion } from './request'
import type { AiAction, AiConfig, AiConfigInput, AiContext, AiReferenceChapters, AiResponse } from '@/types/ai'

let session: Promise<string> | undefined
const base = () => getApiBaseUrl().replace(/\/$/, '')

async function token(): Promise<string> {
  if (!session) session = (async () => {
    const response = await fetch(`${base()}/ai/session`, { headers: { Accept: 'application/json' }, cache: 'no-store' })
    if (!response.ok) throw Error('AI 本机访问验证失败，请从工作室地址打开页面。')
    const value = await response.json()
    if (typeof value.token !== 'string' || !value.token) throw Error('AI 会话不可用，请重试。')
    return value.token
  })().catch(error => { session = undefined; throw error })
  return session
}

async function call<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal, renewReadSession = false): Promise<T> {
  const key = await token()
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  let response: Response
  try {
    response = await fetch(`${base()}${path}`, {
      method, signal, cache: 'no-store',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-AI-Session': key },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    // Never surface a request object containing a key or manuscript.
    throw Error('无法连接 AI 服务。若请求已发出，可能已产生费用，请检查状态后手动重试。')
  }
  let value: any
  try { value = await response.json() } catch { throw Error('AI 服务返回了无法识别的结果。') }
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) session = undefined
    // A backend restart invalidates in-memory sessions. Only an explicitly safe GET
    // may renew once; model generation, model tests and writes are never replayed.
    if (response.status === 401 && method === 'GET' && renewReadSession) return call<T>(path, method, body, signal, false)
    throw Error(typeof value.message === 'string' ? value.message.slice(0, 500) : `AI 请求未完成（${response.status}）。`)
  }
  return value as T
}

export const aiApi = {
  config: () => call<AiConfig>('/ai/config', 'GET', undefined, undefined, true),
  saveConfig: (body: AiConfigInput) => call<AiConfig>('/ai/config', 'PUT', body),
  test: (expectedRevision: string) => call<{ message?: string; success?: boolean }>('/ai/test', 'POST', { expectedRevision }),
  cancel: (id: string) => call<unknown>(`/ai/cancel/${encodeURIComponent(id)}`, 'POST'),
  async referenceChapters(novelId: number, signal?: AbortSignal) {
    const epoch = getNovelContextVersion()
    const value = await call<AiReferenceChapters>(`/novels/${novelId}/ai/reference-chapters`, 'GET', undefined, signal, true)
    if (epoch !== getNovelContextVersion() || signal?.aborted) throw new DOMException('作品已切换', 'AbortError')
    if (value?.maxChars !== 10000 || !Array.isArray(value.chapters) || value.chapters.some(chapter =>
      !chapter || typeof chapter.uid !== 'string' || !chapter.uid || typeof chapter.title !== 'string' || !Number.isSafeInteger(chapter.wordCount) || chapter.wordCount < 0
    ) || new Set(value.chapters.map(chapter => chapter.uid)).size !== value.chapters.length) throw Error('正文章节列表无效，请刷新后重试。')
    return value
  },
  async contextCurrent(novelId: number, action: AiAction, context: AiContext, snapshotId: string) {
    const epoch = getNovelContextVersion()
    const value = await call<{ current: boolean }>(`/novels/${novelId}/ai/context-check`, 'POST', { action, context, snapshotId })
    if (epoch !== getNovelContextVersion()) throw new DOMException('作品已切换', 'AbortError')
    if (typeof value.current !== 'boolean') throw Error('参考资料核对结果无效，没有采用内容。')
    return value
  },
  async suggest(novelId: number, action: AiAction, context: AiContext, requestId: string, signal: AbortSignal, expectedConfigRevision: string) {
    const epoch = getNovelContextVersion()
    const result = await call<AiResponse>(`/novels/${novelId}/ai/suggestions`, 'POST', { action, context, requestId, expectedConfigRevision }, signal)
    if (epoch !== getNovelContextVersion() || signal.aborted) throw new DOMException('作品已切换', 'AbortError')
    if (result.requestId !== requestId || result.action !== action) throw Error('AI 返回的任务不匹配，结果已丢弃。')
    return result
  },
}
