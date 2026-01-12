import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { TimelineEvent, TimelineEventCreateDTO, TimelineEventUpdateDTO, TimelineEventRelations } from '@/types/timeline'
import { timelineApi } from '@/api/timeline'

export const useTimelineStore = defineStore('timeline', () => {
  const events = ref<TimelineEvent[]>([])
  const loading = ref(false)
  const searchKeyword = ref('')

  const filteredEvents = computed(() => {
    if (!searchKeyword.value) return events.value
    const keyword = searchKeyword.value.toLowerCase()
    return events.value.filter(e =>
      e.title.toLowerCase().includes(keyword) ||
      e.description?.toLowerCase().includes(keyword)
    )
  })

  const sortedEvents = computed(() => {
    return [...filteredEvents.value].sort((a, b) => {
      if (a.realOrder !== undefined && b.realOrder !== undefined) {
        return a.realOrder - b.realOrder
      }
      return 0
    })
  })

  async function fetchEvents() {
    loading.value = true
    try {
      events.value = await timelineApi.getAll()
    } finally {
      loading.value = false
    }
  }

  async function createEvent(data: TimelineEventCreateDTO) {
    const event = await timelineApi.create(data)
    events.value.push(event)
    return event
  }

  async function updateEvent(id: number, data: TimelineEventUpdateDTO) {
    const updated = await timelineApi.update(id, data)
    const index = events.value.findIndex(e => e.id === id)
    if (index !== -1) {
      events.value[index] = updated
    }
    return updated
  }

  async function deleteEvent(id: number) {
    await timelineApi.delete(id)
    events.value = events.value.filter(e => e.id !== id)
  }

  async function setEventRelations(id: number, relations: TimelineEventRelations) {
    await timelineApi.setRelations(id, relations)
    await fetchEvents()
  }

  async function setEventTags(id: number, tagIds: number[]) {
    await timelineApi.setTags(id, tagIds)
    await fetchEvents()
  }

  function setSearchKeyword(keyword: string) {
    searchKeyword.value = keyword
  }

  function $reset() {
    events.value = []
    searchKeyword.value = ''
  }

  return {
    events, loading, searchKeyword, filteredEvents, sortedEvents,
    fetchEvents, createEvent, updateEvent, deleteEvent,
    setEventRelations, setEventTags, setSearchKeyword, $reset
  }
})
