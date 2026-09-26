export const studioModules = [
  { path: 'overview', name: '创作总览', hint: '故事的全貌', group: '工作台' },
  {
    path: 'outlines',
    name: '章节大纲',
    hint: '安排叙事节奏',
    group: '故事脉络',
  },
  {
    path: 'timeline',
    name: '故事时间线',
    hint: '串起关键时刻',
    group: '故事脉络',
  },
  {
    path: 'foreshadows',
    name: '伏笔线索',
    hint: '埋下一枚种子',
    group: '故事脉络',
  },
  {
    path: 'characters',
    name: '人物档案',
    hint: '让角色鲜活起来',
    group: '世界构建',
  },
  {
    path: 'relationships',
    name: '人物关系',
    hint: '看见命运的交织',
    group: '世界构建',
  },
  {
    path: 'worldview',
    name: '世界设定',
    hint: '定义世界的规则',
    group: '世界构建',
  },
  {
    path: 'scenes',
    name: '场景画册',
    hint: '故事发生的地方',
    group: '世界构建',
  },
  { path: 'map', name: '世界地图', hint: '展开故事的疆域', group: '世界构建' },
  { path: 'tags', name: '标签索引', hint: '整理你的灵感', group: '世界构建' },
]
export interface StudioRecord {
  id: number
  name?: string
  title?: string
  updatedAt?: string
  createdAt?: string
  status?: string
}
export function recentRecords(
  groups: { path: string; label: string; items: StudioRecord[] }[],
) {
  return groups
    .flatMap((group) =>
      group.items.map((item) => ({
        ...item,
        path: group.path,
        label: group.label,
      })),
    )
    .sort(
      (a, b) =>
        (Date.parse(b.updatedAt || b.createdAt || '') || 0) -
        (Date.parse(a.updatedAt || a.createdAt || '') || 0),
    )
    .slice(0, 5)
}
export function remainingSeconds(deadline: number, now: number) {
  return Math.max(0, Math.ceil((deadline - now) / 1000))
}
export function notebookKey(novelId: number) {
  return `ink-studio-note-${novelId}`
}
export function filterNovels<
  T extends {
    title: string
    description?: string
    status?: string
    author?: string
    updatedAt?: string
  },
>(novels: T[], query: string, status: string, sort: string) {
  const term = query.trim().toLocaleLowerCase()
  return novels
    .filter(
      (n) =>
        (!term ||
          `${n.title} ${n.author || ''} ${n.description || ''}`
            .toLocaleLowerCase()
            .includes(term)) &&
        (status === 'all' || n.status === status),
    )
    .sort((a, b) =>
      sort === 'title'
        ? a.title.localeCompare(b.title, 'zh-CN')
        : (Date.parse(b.updatedAt || '') || 0) -
          (Date.parse(a.updatedAt || '') || 0),
    )
}
