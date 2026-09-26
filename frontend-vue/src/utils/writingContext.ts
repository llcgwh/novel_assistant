import type { Resource, WritingLink } from '@/types/writing'

export interface ForeshadowPlan {
  uid: string
  role: string
  done: boolean
}

export function foreshadowPlans(links: WritingLink[]): ForeshadowPlan[] {
  return links.flatMap((link) => {
    if (link.type !== 'foreshadows' || !link.plannedRole) return []
    return [
      {
        uid: link.uid,
        role: link.plannedRole,
        done: link.role === link.plannedRole,
      },
    ]
  })
}

export function setPlanDone(links: WritingLink[], uid: string, done: boolean) {
  return links.map((link) => {
    return link.uid === uid && link.type === 'foreshadows' && link.plannedRole
      ? { ...link, role: done ? link.plannedRole : 'reference' }
      : link
  })
}

export function chapterTasks(notes: string) {
  return notes.split('\n').flatMap((line, index) => {
    if (!/^\s*-?\s*\[[ xX]\]/.test(line)) return []
    return [
      {
        index,
        done: /^\s*-?\s*\[[xX]\]/.test(line),
        text: line.replace(/^\s*-?\s*\[[ xX]\]\s*/, '').trim(),
      },
    ]
  })
}

export function addRevisionNote(
  notes: string,
  title: string,
  text: string,
  chapter: string,
  block: string,
) {
  return [
    notes.trimEnd(),
    `- [ ] 修订：${title.replace(/\s+/g, ' ').trim()}\n  来源：${chapter.replace(/\s+/g, ' ')}${block ? ` · 段落 ${block}` : ''}\n  原文：${text.replace(/\r?\n/g, '\n  ')}`,
  ]
    .filter(Boolean)
    .join('\n')
}

export function timelineNeighbors(resources: Resource[], id: number) {
  const events = resources
    .filter((item) => item.type === 'timeline')
    .sort((a, b) => {
      const rank = (item: Resource) =>
        typeof item.raw.realOrder === 'number'
          ? item.raw.realOrder
          : Number.MAX_SAFE_INTEGER
      return rank(a) - rank(b) || a.id - b.id
    })
  const index = events.findIndex((item) => item.id === id)
  return index < 0
    ? { previous: undefined, next: undefined }
    : { previous: events[index - 1], next: events[index + 1] }
}

export function linkedResources(
  resources: Resource[],
  links: WritingLink[],
  type?: string,
  block?: string,
) {
  const ids = new Set(
    links
      .filter(
        (link) =>
          (!type || link.type === type) &&
          (block === undefined || !link.blockId || link.blockId === block),
      )
      .map((link) => `${link.type}:${link.targetId}`),
  )
  return resources.filter((resource) =>
    ids.has(`${resource.type}:${resource.id}`),
  )
}

export function relatedResources(
  resources: Resource[],
  source: Resource,
  field: string,
  type: string,
) {
  const ids = new Set(
    (Array.isArray(source.raw[field]) ? source.raw[field] : []).map(
      (item: any) => (typeof item === 'number' ? item : item.id),
    ),
  )
  return resources.filter((item) => item.type === type && ids.has(item.id))
}
