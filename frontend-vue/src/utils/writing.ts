import type { Chapter, DocNode, SessionRecord } from '@/types/writing'
export const linkRoles: Record<string, string> = {
  reference: '涉及',
  viewpoint: '视角人物',
  current: '此刻事件',
  laid: '埋设',
  hint: '暗示',
  developed: '推进',
  revealed: '揭示',
}
export const resourceNames: Record<string, string> = {
  characters: '人物',
  timeline: '时间线',
  foreshadows: '伏笔',
  outlines: '大纲',
  scenes: '场景',
  worldview: '设定',
  map: '地点',
  tags: '标签',
}
export const chapterStatuses: Record<string, string> = {
  draft: '草稿',
  writing: '写作中',
  revision: '待修订',
  final: '定稿',
}
const cjk =
  /([\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}])/gu
export function wordCount(text: string) {
  return (
    text
      .replace(cjk, ' $1 ')
      .match(
        /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]|[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*/gu,
      ) || []
  ).length
}
export function documentText(doc: DocNode): string {
  if (doc.type === 'text') return doc.text || ''
  if (doc.type === 'hardBreak') return '\n'
  return (doc.content || [])
    .map(
      (n) =>
        documentText(n) +
        (['paragraph', 'heading', 'horizontalRule'].includes(n.type)
          ? '\n'
          : ''),
    )
    .join('')
}
export function emptyDoc(): DocNode {
  return {
    type: 'doc',
    content: [{ type: 'paragraph', attrs: { id: crypto.randomUUID() } }],
  }
}
export function plainDocument(text: string): DocNode {
  return {
    type: 'doc',
    content: text
      .replace(/\r\n?/g, '\n')
      .split('\n')
      .map((line) => ({
        type: 'paragraph',
        attrs: { id: crypto.randomUUID() },
        ...(line ? { content: [{ type: 'text', text: line }] } : {}),
      })),
  }
}
export function blockList(doc: DocNode): { id: string; text: string }[] {
  const rows: { id: string; text: string }[] = []
  function walk(n: DocNode) {
    if (n.attrs?.id) rows.push({ id: n.attrs.id, text: documentText(n) })
    n.content?.forEach(walk)
  }
  walk(doc)
  return rows
}
export function fingerprint(chapter: Chapter) {
  const { revision, updatedAt, mutationId, wordCount, position, ...content } =
    chapter
  return JSON.stringify(content)
}
export function localDate(now = new Date()) {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}
// Older manuscript responses serialized LocalDateTime as [year, month, day, ...].
export function latestChapter<
  T extends { deleted?: boolean; updatedAt?: unknown },
>(chapters: T[]): T | undefined {
  function time(value: unknown) {
    if (
      Array.isArray(value) &&
      value.length >= 3 &&
      value.every(Number.isFinite)
    ) {
      const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] =
        value
      return (
        new Date(
          year,
          month - 1,
          day,
          hour,
          minute,
          second,
          Math.floor(nano / 1000000),
        ).getTime() || 0
      )
    }
    return typeof value === 'string' ? Date.parse(value) || 0 : 0
  }
  return chapters
    .filter((c) => !c.deleted)
    .sort((a, b) => time(b.updatedAt) - time(a.updatedAt))[0]
}
export function elapsedLabel(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`
}
export function parseManuscript(text: string, filename: string) {
  const lines = text
    .replace(/^\uFEFF/, '')
    .replace(/\r\n?/g, '\n')
    .split('\n')
  const chapters: { title: string; doc: DocNode }[] = []
  let title = filename.replace(/\.(txt|md|markdown)$/i, ''),
    body: string[] = []
  function finish() {
    if (body.some((x) => x.trim()) || chapters.length)
      chapters.push({
        title: title || '未命名章节',
        doc: plainDocument(body.join('\n').trim()),
      })
    body = []
  }
  for (const line of lines) {
    const heading = line.match(
      /^(?:#{1,3}\s+(.+)|((?:第[零〇一二三四五六七八九十百千万两\d]+[章节回卷篇].*|序章.*|楔子.*|番外.*)))$/,
    )
    if (heading) {
      if (body.some((x) => x.trim())) finish()
      title = (heading[1] || heading[2]).trim()
    } else body.push(line)
  }
  finish()
  if (!chapters.length)
    chapters.push({ title: title || '导入章节', doc: emptyDoc() })
  return chapters
}
/** Session counters are cumulative; the server upserts them by uid + sequence. */
export class WritingMeter {
  record: SessionRecord
  private startCount: number
  private events: { at: number; count: number }[] = []
  private lastEdit = 0
  private started: number
  private lastTick: number
  paused = false
  constructor(chapterUid: string, text: string, now = Date.now()) {
    this.startCount = wordCount(text)
    this.started = now
    this.lastTick = now
    this.record = {
      uid: crypto.randomUUID(),
      sequence: 0,
      date: localDate(new Date(now)),
      chapterUid,
      typed: 0,
      pasted: 0,
      net: 0,
      activeSeconds: 0,
      peak: 0,
    }
  }
  rollover(now = Date.now()): SessionRecord | null {
    const date = localDate(new Date(now))
    if (this.record.date === date) return null
    const previous = { ...this.record }
    this.startCount += previous.net
    this.record = {
      uid: crypto.randomUUID(),
      sequence: 0,
      date,
      chapterUid: previous.chapterUid,
      typed: 0,
      pasted: 0,
      net: 0,
      activeSeconds: 0,
      peak: 0,
    }
    this.events = []
    this.started = now
    this.lastTick = now
    return previous
  }
  update(
    before: string,
    after: string,
    source: 'typed' | 'paste' | 'history' | 'restore',
    now = Date.now(),
  ) {
    this.tick(now)
    if (source === 'restore') {
      this.startCount = wordCount(after) - this.record.net
      return
    }
    this.record.net = wordCount(after) - this.startCount
    this.record.sequence++
    if (source === 'history') return
    // Count the replaced range with one word of context to avoid treating every Latin key as a new word.
    let a = 0,
      z = 0
    while (a < before.length && a < after.length && before[a] === after[a]) a++
    while (
      z < before.length - a &&
      z < after.length - a &&
      before[before.length - z - 1] === after[after.length - z - 1]
    )
      z++
    const left = after.slice(0, a).match(/[\p{L}\p{N}'’]+$/u)?.[0] || ''
    const added = after.slice(a, after.length - z)
    const count = Math.max(0, wordCount(left + added) - wordCount(left))
    if (source === 'paste') this.record.pasted += count
    else {
      this.record.typed += count
      if (count && !this.paused) this.events.push({ at: now, count })
    }
    this.lastEdit = now
  }
  tick(now = Date.now()) {
    const before = this.record.activeSeconds,
      peak = this.record.peak
    const delta = Math.max(0, Math.min(5000, now - this.lastTick))
    if (!this.paused && this.lastEdit && now - this.lastEdit < 60000)
      this.record.activeSeconds += delta / 1000
    this.lastTick = now
    this.events = this.events.filter((e) => e.at > now - 60000)
    if (now - this.started >= 60000)
      this.record.peak = Math.max(this.record.peak, this.rate(now))
    if (before !== this.record.activeSeconds || peak !== this.record.peak)
      this.record.sequence++
  }
  rate(now = Date.now()) {
    return this.events
      .filter((e) => e.at > now - 60000)
      .reduce((n, e) => n + e.count, 0)
  }
}
