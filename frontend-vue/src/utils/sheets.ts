import { studioNavigation } from '@/utils/studio'

const sheetProfiles = [
  {
    id: 'overview',
    title: '故事的另一面',
    caption: '一页灵感，无限可能',
    label: '创作总览',
    layout: 'hero',
    tint: '#78dfc1',
    motif: 'orbit',
  },
  {
    id: 'writing',
    title: '故事就在笔尖',
    caption: '写作罗盘',
    label: '写作工作台',
    layout: 'right',
    tint: '#dfc491',
    motif: 'writing',
  },
  {
    id: 'characters',
    title: '每个人，都是一个宇宙',
    caption: '人物索引',
    label: '人物档案',
    layout: 'right',
    tint: '#bba2ff',
    motif: 'portrait',
  },
  {
    id: 'outlines',
    title: '故事，自有它的节奏',
    caption: '章节进度',
    label: '章节大纲',
    layout: 'wide',
    tint: '#8fbfff',
    motif: 'chapters',
  },
  {
    id: 'timeline',
    title: '让时间，有迹可循',
    caption: '事件导览',
    label: '故事时间线',
    layout: 'left',
    tint: '#edc987',
    motif: 'timeline',
  },
  {
    id: 'foreshadows',
    title: '伏下的笔，终有回声',
    caption: '线索索引',
    label: '伏笔线索',
    layout: 'right',
    tint: '#efa9bc',
    motif: 'threads',
  },
  {
    id: 'worldview',
    title: '在规则之外，想象世界',
    caption: '设定索引',
    label: '世界设定',
    layout: 'left',
    tint: '#90d5c0',
    motif: 'world',
  },
  {
    id: 'scenes',
    title: '让一个瞬间，有了形状',
    caption: '场景取景器',
    label: '场景画册',
    layout: 'wide',
    tint: '#e2b995',
    motif: 'scenes',
  },
  {
    id: 'relationships',
    title: '所有相遇，都有引力',
    caption: '关系中的人物',
    label: '人物关系',
    layout: 'wide',
    tint: '#c4a5ee',
    motif: 'constellation',
  },
  {
    id: 'map',
    title: '故事的疆域，由此展开',
    caption: '地点导航',
    label: '世界地图',
    layout: 'wide',
    tint: '#91c7e2',
    motif: 'map',
  },
  {
    id: 'tags',
    title: '把散落的灵感，串起来',
    caption: '标签一览',
    label: '标签索引',
    layout: 'right',
    tint: '#c4d591',
    motif: 'tags',
  },
  {
    id: 'search',
    title: '每条线索，都值得找到',
    caption: '资料雷达',
    label: '全局搜索',
    layout: 'right',
    tint: '#8fcbe4',
    motif: 'radar',
  },
  {
    id: 'settings',
    title: '为灵感，调好光线',
    caption: '工作室状态',
    label: '设置与备份',
    layout: 'left',
    tint: '#c1c7d6',
    motif: 'settings',
  },
] as const

export type SheetId = (typeof sheetProfiles)[number]['id']
// One navigation order drives the sidebar, numbering, arrows and flight direction.
export const sheets = studioNavigation.map((item) => {
  const profile = sheetProfiles.find((sheet) => sheet.id === item.path)
  if (!profile) throw Error(`缺少创作模块折页配置：${item.path}`)
  return { ...profile, label: item.name }
})
export function sheetFor(id: string) {
  return sheets.find((sheet) => sheet.id === id) || sheets[0]
}
export function sheetSerial(id: string) {
  return String(
    Math.max(
      0,
      sheets.findIndex((sheet) => sheet.id === id),
    ) + 1,
  ).padStart(2, '0')
}
export function adjacentSheet(id: string, direction: number) {
  const index = Math.max(
    0,
    sheets.findIndex((sheet) => sheet.id === id),
  )
  return sheets[(index + direction + sheets.length) % sheets.length]
}

// All completion paths belong to the latest navigation, including cancelled animations.
// A new navigation or disposal must invalidate both the mid-flip content swap and finish.
export function createSheetFlight(clock: {
  set: (callback: () => void, delay: number) => unknown
  clear: (timer: unknown) => void
}) {
  let generation = 0,
    timer: unknown,
    active: { cancel: () => void } | undefined
  function cancel() {
    generation++
    if (timer !== undefined) clock.clear(timer)
    timer = undefined
    active?.cancel()
    active = undefined
  }
  function run(
    animation: { cancel: () => void; finished: Promise<unknown> },
    duration: number,
    swap: () => void,
    settle: () => void,
  ) {
    cancel()
    const ticket = generation
    active = animation
    timer = clock.set(() => {
      if (ticket === generation) swap()
    }, duration / 2)
    void animation.finished.then(
      () => {
        if (ticket !== generation) return
        if (timer !== undefined) clock.clear(timer)
        timer = undefined
        active = undefined
        settle()
      },
      () => {
        /* cancellation is handled by the next flight or immediate placement */
      },
    )
  }
  return { run, cancel }
}
