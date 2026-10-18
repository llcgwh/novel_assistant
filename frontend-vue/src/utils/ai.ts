import type { AiAction, AiCandidate, AiDirection, AiScope } from '@/types/ai'
import type { WritingDesk } from '@/types/writingDesk'

export const aiActions: Record<AiScope, { action: AiAction; label: string; field?: string }[]> = {
  character: [
    { action: 'character.name', label: '想名字', field: 'name' },
    { action: 'character.personality', label: '构思性格', field: 'personality' },
    { action: 'character.background', label: '构思经历', field: 'background' },
    { action: 'character.appearance', label: '构思外貌', field: 'appearance' },
  ],
  location: [{ action: 'location.name', label: '地点起名', field: 'name' }, { action: 'location.description', label: '补地点描述', field: 'description' }],
  scene: [{ action: 'scene.name', label: '场景起名', field: 'name' }, { action: 'scene.description', label: '补场景资料', field: 'description' }],
  worldview: [{ action: 'worldview.content', label: '补充设定', field: 'content' }],
  outline: [{ action: 'outline.review', label: '检查因果与动机' }],
  timeline: [{ action: 'timeline.review', label: '检查时间与顺序' }],
  foreshadow: [{ action: 'foreshadow.review', label: '检查伏笔条件' }],
  relationship: [{ action: 'relationship.review', label: '检查关系动机' }],
  writing: [{ action: 'writing.inspiration', label: '下一步灵感' }],
}

export function aiTargetField(action: AiAction): string | undefined {
  return Object.values(aiActions).flat().find(item => item.action === action)?.field
}

export function aiFieldLabel(action: AiAction): string {
  if (action === 'character.name') return '姓名'
  const field = aiTargetField(action)
  return ({ name: '名称', personality: '性格', background: '背景', appearance: '外貌', description: '描述', content: '设定内容' } as Record<string, string>)[field || ''] || '当前'
}

/** A candidate cannot smuggle other field edits into the form. */
export function aiCandidatePatch(action: AiAction, candidate: AiCandidate): { field: string; value: string } {
  const field = aiTargetField(action)
  const keys = Object.keys(candidate.fields || {})
  if (!field || keys.length !== 1 || keys[0] !== field || typeof candidate.fields[field] !== 'string')
    throw Error('候选字段与当前任务不符，请重新生成。')
  return { field, value: candidate.fields[field] }
}

export function aiDraftKey(draft: Record<string, unknown>, targetId?: number, chapterUid?: string): string {
  return JSON.stringify({ targetId: targetId ?? null, chapterUid: chapterUid ?? null, draft })
}

export function aiUndoPatch(change: { field: string; before: string; applied: string }, current: Record<string, unknown>) {
  if (current[change.field] !== change.applied) throw Error('采用后此字段又被编辑过，不能自动撤销。请对照原值手动调整。')
  return { field: change.field, value: change.before }
}

export function aiCollectionMerge(latest: WritingDesk, previous: WritingDesk, sourceKey: string, mutationId: string): WritingDesk {
  const intended = previous.ideas?.filter(idea => idea.sourceKey === sourceKey) || []
  if (intended.length !== 1) throw Error('待确认收藏缺少对应的灵感记录，请保留本机数据。')
  const exists = latest.ideas?.some(idea => idea.uid === intended[0]!.uid || idea.sourceKey === sourceKey)
  return { ...latest, ideas: [...(latest.ideas || []), ...(exists ? [] : intended)], mutationId }
}

export function aiDirectionNote(direction: AiDirection, model: string, sourceTitles: string[], snapshot?: { id: string; generatedAt: string }): string {
  const sections = [
    'AI 灵感候选（尚未成为正式设定）',
    `剧情目标：${direction.goal}`, `人物选择：${direction.choice}`,
    `可能后果：${direction.consequence}`, `风险与代价：${direction.risk}`, `待作者决定：${direction.question}`,
  ]
  if (direction.assumptions?.length) sections.push(`新增假设：${direction.assumptions.join('；')}`)
  if (sourceTitles.length) sections.push(`参考资料：${sourceTitles.join('、')}`)
  if (model) sections.push(`模型：${model}`)
  if (snapshot) sections.push(`生成时间：${snapshot.generatedAt}`, `参考快照：${snapshot.id}`, '此候选依据生成时的资料，不随当前作品更新；收藏不代表采用为正式设定。')
  return sections.join('\n\n')
}
