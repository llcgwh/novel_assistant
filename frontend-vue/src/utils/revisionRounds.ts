import type { RevisionRound, RevisionTask } from '@/types/writingDesk'

export const ALL_ROUNDS = '__all__'

export function roundTasks(tasks: RevisionTask[], scope: string) {
  return scope === ALL_ROUNDS
    ? tasks
    : tasks.filter((task) => (task.roundUid || '') === scope)
}

export function roundProgress(tasks: RevisionTask[], scope: string) {
  const scoped = roundTasks(tasks, scope)
  const completed = scoped.filter((task) => task.status === 'done').length
  return {
    total: scoped.length,
    completed,
    percent: scoped.length ? Math.round((completed / scoped.length) * 100) : 0,
  }
}

export function roundOptions(rounds: RevisionRound[]) {
  return [...rounds]
    .sort(
      (a, b) =>
        Number(a.status === 'archived') - Number(b.status === 'archived'),
    )
    .map((round) => ({
      value: round.uid,
      label: `${round.title}${round.status === 'archived' ? ' · 已归档' : ''}`,
    }))
}

export function createRevisionRound(
  now = new Date().toISOString(),
): RevisionRound {
  return {
    uid: crypto.randomUUID(),
    title: '',
    goal: '',
    status: 'active',
    createdAt: now,
    updatedAt: now,
  }
}
