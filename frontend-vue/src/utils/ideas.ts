import type { Idea } from '@/types/writingDesk'

export const ideaCategories = [
  { value: 'other', label: '其他' },
  { value: 'plot', label: '情节' },
  { value: 'character', label: '人物' },
  { value: 'setting', label: '设定' },
  { value: 'dialogue', label: '对白' },
  { value: 'scene', label: '场景' },
] as const

const IDEA_BODY_LIMIT = 20000
const CHAPTER_NOTES_LIMIT = 50000
export const IDEA_DRAG_MIME = 'application/x-novel-assistant-idea'

export function ideaCategoryLabel(value: string) {
  return ideaCategories.find((category) => category.value === value)?.label || '其他'
}

export function ideaSignature(idea: Idea | null | undefined): string {
  return idea
    ? JSON.stringify([
        idea.uid,
        idea.title,
        idea.body,
        idea.category,
        idea.chapterUids,
        idea.createdAt,
        idea.sourceKey || '',
      ])
    : ''
}

interface IdeaChapter {
  uid: string
  title: string
}

function markdownLabel(value: string): string {
  return value.replace(/[\\`*_{}\[\]<>#|]/g, '\\$&')
}

export function markdownIdeas(
  ideas: Idea[],
  chapters: IdeaChapter[],
  heading = '灵感卡片',
): string {
  const titles = new Map(chapters.map((chapter) => [chapter.uid, chapter.title]))
  return [
    `# ${markdownLabel(heading)}`,
    ...ideas.map((idea) => {
      const chapterLabels = idea.chapterUids.map((uid) =>
        titles.has(uid)
          ? markdownLabel(titles.get(uid) || '未命名章节')
          : `不可用章节（${markdownLabel(uid)}）`,
      )
      return [
        `## ${markdownLabel(idea.title || '未命名灵感')}`,
        `分类：${ideaCategoryLabel(idea.category)}`,
        `关联章节：${chapterLabels.length ? chapterLabels.join('、') : '未关联章节'}`,
        idea.body,
      ].join('\n\n')
    }),
  ].join('\n\n') + '\n'
}

// Hash UTF-16 code units so even unusual legacy strings keep their exact identity.
// This is an identity checksum, not a security or password hash.
function sourceHash(text: string): string {
  let hash = 0xcbf29ce484222325n
  for (let index = 0; index < text.length; index++) {
    const code = text.charCodeAt(index)
    hash = BigInt.asUintN(64, (hash ^ BigInt(code & 0xff)) * 0x100000001b3n)
    hash = BigInt.asUintN(64, (hash ^ BigInt(code >>> 8)) * 0x100000001b3n)
  }
  return hash.toString(16).padStart(16, '0')
}

export function createLegacyIdeas(
  text: string,
  novelId: number,
  now = new Date().toISOString(),
): Idea[] {
  if (text === '') return []
  const identity = `${sourceHash(String(novelId))}-${sourceHash(text)}`
  const chunks: string[] = []
  for (let start = 0; start < text.length;) {
    let end = Math.min(start + IDEA_BODY_LIMIT, text.length)
    // Avoid splitting an emoji into two individually invalid surrogate strings.
    if (
      end < text.length &&
      text.charCodeAt(end - 1) >= 0xd800 &&
      text.charCodeAt(end - 1) <= 0xdbff &&
      text.charCodeAt(end) >= 0xdc00 &&
      text.charCodeAt(end) <= 0xdfff
    ) {
      end--
    }
    chunks.push(text.slice(start, end))
    start = end
  }
  return chunks.map((body, index) => ({
    uid: `legacy-idea-${identity}-${index + 1}`,
    title: chunks.length === 1 ? '旧灵感便笺' : `旧灵感便笺（${index + 1}/${chunks.length}）`,
    body,
    category: 'other',
    chapterUids: [],
    createdAt: now,
    updatedAt: now,
    sourceKey: `legacy-notebook:v1:${identity}:${index + 1}`,
  }))
}

function ideaNoteMarker(uid: string): string {
  // Encode code units rather than raw text: a UID cannot inject a comment boundary.
  const encoded: string[] = []
  for (let index = 0; index < uid.length; index++) {
    encoded.push(uid.charCodeAt(index).toString(16).padStart(4, '0'))
  }
  return `<!-- novel-assistant:idea:${encoded.join('')} -->`
}

export function appendIdeaToNotes(notes: string, idea: Idea): string {
  // Read earlier copies without adding technical markers to an author's notes.
  const legacyCopy = `${ideaNoteMarker(idea.uid)}\n## ${idea.title || '未命名灵感'}\n\n${idea.body}`
  const addition = `## 灵感：${idea.title || idea.body.split(/\r?\n/).find(line => line.trim())?.slice(0, 80) || '未命名灵感'}\n\n${idea.body}`
  const hasCompleteCopy = (copy: string) => {
    let start = notes.indexOf(copy)
    while (start >= 0) {
      const end = start + copy.length
      if ((start === 0 || notes.slice(start - 2, start) === '\n\n') &&
        (end === notes.length || notes.startsWith('\n\n## 灵感：', end) || notes.startsWith('\n\n<!-- novel-assistant:idea:', end))) return true
      start = notes.indexOf(copy, start + 1)
    }
    return false
  }
  if (hasCompleteCopy(addition) || hasCompleteCopy(legacyCopy)) return notes
  const next = notes + (notes ? '\n\n' : '') + addition
  if (next.length > CHAPTER_NOTES_LIMIT) {
    throw new Error('章节备注最多可保存 50000 字符，请整理备注后再加入灵感。')
  }
  return next
}

export interface IdeaDragReference {
  uid: string
  novelId: number
}

export function readIdeaDrag(
  payload: string,
  novelId: number,
): IdeaDragReference | null {
  if (!payload || payload.length > 2048 || !Number.isSafeInteger(novelId) || novelId <= 0) {
    return null
  }
  try {
    const reference: unknown = JSON.parse(payload)
    if (!reference || typeof reference !== 'object' || Array.isArray(reference)) return null
    const value = reference as Record<string, unknown>
    if (
      Object.keys(value).length !== 2 ||
      typeof value.uid !== 'string' ||
      !value.uid.trim() ||
      value.uid !== value.uid.trim() ||
      value.uid.length > 100 ||
      /[\u0000-\u001f\u007f]/.test(value.uid) ||
      value.novelId !== novelId
    ) {
      return null
    }
    return { uid: value.uid, novelId }
  } catch {
    return null
  }
}
