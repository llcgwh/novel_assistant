import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ID } from '@/types'
import type { MapLocation, MapLocationCreateDTO, MapLocationUpdateDTO } from '@/types/map'
import { mapLocationsApi } from '@/api/mapLocations'
import { imagesApi } from '@/api/images'

export const useMapStore = defineStore('map', () => {
  const locations = ref<MapLocation[]>([])
  const loading = ref(false)
  const backgroundImageId = ref<number | null>(null)
  const backgroundImageUrl = ref<string | null>(null)

  async function fetchLocations() {
    loading.value = true
    try {
      locations.value = await mapLocationsApi.getAll()
    } finally {
      loading.value = false
    }
  }

  async function fetchLocationsByType(type: string) {
    loading.value = true
    try {
      locations.value = await mapLocationsApi.getByType(type)
    } finally {
      loading.value = false
    }
  }

  async function createLocation(data: MapLocationCreateDTO) {
    const location = await mapLocationsApi.create(data)
    locations.value.push(location)
    return location
  }

  async function updateLocation(id: number, data: MapLocationUpdateDTO) {
    const updated = await mapLocationsApi.update(id, data)
    const index = locations.value.findIndex(l => l.id === id)
    if (index !== -1) {
      locations.value[index] = updated
    }
    return updated
  }

  async function deleteLocation(id: number) {
    await mapLocationsApi.delete(id)
    locations.value = locations.value.filter(l => l.id !== id)
  }

  async function setLocationTags(id: ID, tagIds: ID[]) {
    await mapLocationsApi.setTags(id, tagIds)
    await fetchLocations()
  }

  async function loadBackgroundFromStorage(novelId: number) {
    backgroundImageId.value = null
    backgroundImageUrl.value = null
    const saved = localStorage.getItem(`mapBackground_${novelId}`)
    if (saved === 'none') return
    const images = await imagesApi.getByType('map_background')
    const selected = images.find(image => image.id === Number(saved))
      || images.sort((a, b) => b.id - a.id)[0]
    if (selected) {
      backgroundImageId.value = selected.id
      backgroundImageUrl.value = imagesApi.getFileUrl(selected.id)
      localStorage.setItem(`mapBackground_${novelId}`, String(selected.id))
    }
  }

  async function setBackground(file: File, novelId: number) {
    const uploaded = await imagesApi.upload(file, 'map_background')
    backgroundImageId.value = uploaded.id
    backgroundImageUrl.value = imagesApi.getFileUrl(uploaded.id)
    localStorage.setItem(`mapBackground_${novelId}`, String(uploaded.id))
  }

  function clearBackground(novelId: number) {
    backgroundImageUrl.value = null
    backgroundImageId.value = null
    localStorage.setItem(`mapBackground_${novelId}`, 'none')
  }

  function $reset() {
    locations.value = []
    backgroundImageId.value = null
    backgroundImageUrl.value = null
  }

  return {
    locations, loading, backgroundImageId, backgroundImageUrl,
    fetchLocations, fetchLocationsByType, createLocation, updateLocation, deleteLocation, setLocationTags,
    loadBackgroundFromStorage, setBackground, clearBackground, $reset
  }
})
