import type { Chapter } from '@/types/writing'

export class ReaderContextExpiredError extends Error {
  constructor() {
    super('阅读作品已切换，忽略迟到的正文请求')
  }
}

/** One immutable novel/book context per reader. No writes or writing statistics. */
export class ReaderChapterCache {
  readonly chapters: Map<string, Chapter>
  private requests = new Map<string, Promise<Chapter>>()
  private disposed = false

  constructor(
    private readonly options: {
      novelId: number
      bookUid: string
      load: (novelId: number, uid: string) => Promise<Chapter>
      isActive?: () => boolean
      chapters?: Map<string, Chapter>
      limit?: number
    },
  ) {
    this.chapters = options.chapters || new Map()
  }

  private assertActive() {
    if (this.disposed || this.options.isActive?.() === false)
      throw new ReaderContextExpiredError()
  }
  async get(uid: string): Promise<Chapter> {
    this.assertActive()
    const cached = this.chapters.get(uid)
    if (cached) {
      this.chapters.delete(uid)
      this.chapters.set(uid, cached)
      return cached
    }
    const existing = this.requests.get(uid)
    if (existing) return existing
    const request = this.options
      .load(this.options.novelId, uid)
      .then((chapter) => {
        this.assertActive()
        if (chapter.uid !== uid || chapter.deleted)
          throw Error('章节已删除或与请求不符')
        this.chapters.set(uid, chapter)
        return chapter
      })
      .finally(() => {
        this.requests.delete(uid)
      })
    this.requests.set(uid, request)
    return request
  }
  trim(protectedUids: Iterable<string> = []) {
    const keep = new Set(protectedUids)
    for (const uid of this.chapters.keys()) {
      if (this.chapters.size <= (this.options.limit || 7)) break
      if (!keep.has(uid)) this.chapters.delete(uid)
    }
  }
  dispose() {
    this.disposed = true
    this.requests.clear()
    this.chapters.clear()
  }
}
