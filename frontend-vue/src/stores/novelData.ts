import { useCharactersStore } from './characters'
import { useScenesStore } from './scenes'
import { useForeshadowsStore } from './foreshadows'
import { useOutlinesStore } from './outlines'
import { useTimelineStore } from './timeline'
import { useMapStore } from './map'
import { useRelationshipsStore } from './relationships'
import { useRelationshipGroupsStore } from './relationshipGroups'
import { useTagsStore } from './tags'
import { useWorldviewStore } from './worldview'

export function resetNovelData() {
  useCharactersStore().$reset()
  useScenesStore().$reset()
  useForeshadowsStore().$reset()
  useOutlinesStore().$reset()
  useTimelineStore().$reset()
  useMapStore().$reset()
  useRelationshipsStore().$reset()
  useRelationshipGroupsStore().$reset()
  useTagsStore().$reset()
  useWorldviewStore().$reset()
}
