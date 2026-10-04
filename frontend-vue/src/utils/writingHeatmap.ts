import type { SessionRecord, WritingStats } from '@/types/writing'
import { localDate } from '@/utils/writing'

export interface WritingDay {
  date: string
  net: number
  typed: number
  pasted: number
  activeSeconds: number
  sessions: number
  chapters: number
  goal: number | null
  revisionSaves: number
  finalTransitions: number
  completedChapters: number
  focusSeconds: number
  focusCompleted: number
}

export interface HeatmapDay extends WritingDay {
  index: number
  column: number
  row: number
}

/** Session records are cumulative snapshots: count only the newest sequence. */
export function writingHeatmap(
  sessions: SessionRecord[],
  today = new Date(),
  stats?: WritingStats | null,
) {
  const latest = new Map<string, SessionRecord>()
  for (const session of sessions) {
    const previous = latest.get(session.uid)
    if (!previous || session.sequence > previous.sequence)
      latest.set(session.uid, session)
  }
  const records = new Map<string, WritingDay & { chapterIds: Set<string> }>()
  for (const session of latest.values()) {
    const day = records.get(session.date) ?? {
      date: session.date,
      net: 0,
      typed: 0,
      pasted: 0,
      activeSeconds: 0,
      sessions: 0,
      chapters: 0,
      goal: null,
      revisionSaves: 0,
      finalTransitions: 0,
      completedChapters: 0,
      focusSeconds: 0,
      focusCompleted: 0,
      chapterIds: new Set<string>(),
    }
    day.net += finite(session.net)
    day.typed += Math.max(0, finite(session.typed))
    day.pasted += Math.max(0, finite(session.pasted))
    day.activeSeconds += Math.max(0, finite(session.activeSeconds))
    day.sessions++
    if (session.chapterUid) day.chapterIds.add(session.chapterUid)
    day.chapters = day.chapterIds.size
    records.set(session.date, day)
  }
  // Each row is an accepted daily snapshot, not another activity to add.
  const dailyStats = new Map((stats?.days ?? []).map((day) => [day.date, day]))
  // Local noon and calendar arithmetic avoid UTC boundaries and DST shifts.
  const start = new Date(
    today.getFullYear(),
    today.getMonth(),
    today.getDate(),
    12,
  )
  start.setDate(start.getDate() - 364)
  const firstWeekday = (start.getDay() + 6) % 7
  const days: HeatmapDay[] = Array.from({ length: 365 }, (_, index) => {
    const day = new Date(start)
    day.setDate(start.getDate() + index)
    const date = localDate(day)
    const record = records.get(date)
    const statsDay = dailyStats.get(date)
    return {
      date,
      net: record?.net ?? 0,
      typed: record?.typed ?? 0,
      pasted: record?.pasted ?? 0,
      activeSeconds: record?.activeSeconds ?? 0,
      sessions: record?.sessions ?? 0,
      chapters: record?.chapters ?? 0,
      // Missing historical goals must remain unknown when today's goal changes.
      goal:
        statsDay?.goal != null && Number.isFinite(statsDay.goal)
          ? Math.max(0, Math.round(statsDay.goal))
          : null,
      revisionSaves: count(statsDay?.revisionSaves),
      finalTransitions: count(statsDay?.finalTransitions),
      completedChapters: new Set(
        (statsDay?.completedChapterUids ?? []).filter(
          (uid) => typeof uid === 'string' && uid.length > 0,
        ),
      ).size,
      focusSeconds: count(statsDay?.focusSeconds),
      focusCompleted: count(statsDay?.focusCompleted),
      index,
      column: Math.floor((firstWeekday + index) / 7) + 1,
      row: ((firstWeekday + index) % 7) + 1,
    }
  })
  const months = days
    .filter((day, i) => i === 0 || day.date.endsWith('-01'))
    .map((day) => ({
      date: day.date,
      column: day.column,
      label: Number(day.date.slice(5, 7)) + '月',
    }))
  // A partial month at either edge may share a column with the next label.
  const spacedMonths = months.filter((month, i) =>
    i === 0 ? !months[1] || months[1].column - month.column > 2 : true,
  )
  return { days, months: spacedMonths, columns: days[days.length - 1].column }
}

function finite(value: number) {
  return Number.isFinite(value) ? value : 0
}

function count(value: number | undefined) {
  return value != null && Number.isFinite(value)
    ? Math.max(0, Math.floor(value))
    : 0
}

export function heatmapScale(goal: number | null | undefined) {
  const target =
    goal != null && Number.isFinite(goal) && goal > 0
      ? Math.max(1, Math.round(goal))
      : 2000
  return {
    target,
    quarter: Math.max(1, Math.ceil(target / 4)),
    half: Math.max(1, Math.ceil(target / 2)),
  }
}

export function heatmapLevel(net: number, goal: number | null | undefined) {
  if (net < 0) return -1
  if (!net) return 0
  const { quarter, half, target } = heatmapScale(goal)
  if (net >= target) return 4
  if (net >= half) return 3
  if (net >= quarter) return 2
  return 1
}

export function heatmapFocusIndex(
  index: number,
  key: string,
  days: HeatmapDay[],
) {
  const current = days[index]
  if (!current) return index
  const targets: Record<string, number> = {
    ArrowLeft: index - 7,
    ArrowRight: index + 7,
    ArrowUp: index - 1,
    ArrowDown: index + 1,
    Home: index - (current.row - 1),
    End: index + (7 - current.row),
  }
  return Math.max(0, Math.min(days.length - 1, targets[key] ?? index))
}
