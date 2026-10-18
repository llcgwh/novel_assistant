import { getCurrentNovelId, getNovelContextVersion } from '@/api/request'
import { CanceledError } from 'axios'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ID } from '@/types'
import type { MapLocation, MapLocationCreateDTO, MapLocationUpdateDTO } from '@/types/map'
import { mapLocationsApi } from '@/api/mapLocations'
import { imagesApi } from '@/api/images'
import { validateImageFile } from '@/utils/imageUpload'

export const useMapStore = defineStore('map', () => {
  const locations = ref<MapLocation[]>([])
  const loading = ref(false)
  const backgroundImageId = ref<number | null>(null)
  const backgroundImageUrl = ref<string | null>(null)
  const backgroundUploading = ref(false)
  let backgroundOperation = 0

  function assertBackgroundContext(novelId: number, context = getNovelContextVersion()) {
    if (context !== getNovelContextVersion() || novelId !== getCurrentNovelId()) throw new CanceledError('作品已切换，本次地图背景未应用')
  }

  async function fetchLocations() {
    const context = getNovelContextVersion()
    loading.value = true
    try {
      locations.value = await mapLocationsApi.getAll()
    } finally {
      if (context === getNovelContextVersion()) loading.value = false
    }
  }

  async function fetchLocationsByType(type: string) {
    const context = getNovelContextVersion()
    loading.value = true
    try {
      locations.value = await mapLocationsApi.getByType(type)
    } finally {
      if (context === getNovelContextVersion()) loading.value = false
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
    assertBackgroundContext(novelId)
    if (backgroundUploading.value) return
    const context = getNovelContextVersion(), operation = ++backgroundOperation
    const saved = localStorage.getItem(`mapBackground_${novelId}`)
    if (saved === 'none') { backgroundImageId.value = null; backgroundImageUrl.value = null; return }
    const images = await imagesApi.getByType('map_background')
    assertBackgroundContext(novelId, context)
    if (operation !== backgroundOperation) return
    const selected = images.find(image => image.id === Number(saved))
      || images.sort((a, b) => b.id - a.id)[0]
    if (selected) {
      localStorage.setItem(`mapBackground_${novelId}`, String(selected.id))
      backgroundImageId.value = selected.id
      backgroundImageUrl.value = imagesApi.getFileUrl(selected.id, novelId)
    } else { backgroundImageId.value = null; backgroundImageUrl.value = null }
  }

  async function setBackground(file: File, novelId: number) {
    assertBackgroundContext(novelId)
    if (backgroundUploading.value) throw new Error('地图背景正在上传，请稍候')
    validateImageFile(file)
    const context = getNovelContextVersion(), operation = ++backgroundOperation
    backgroundUploading.value = true
    try {
      const uploaded = await imagesApi.upload(file, 'map_background', novelId)
      assertBackgroundContext(novelId, context)
      if (operation !== backgroundOperation) throw new CanceledError('地图背景选择已改变，本次上传结果未应用')
      localStorage.setItem(`mapBackground_${novelId}`, String(uploaded.id))
      backgroundImageId.value = uploaded.id
      backgroundImageUrl.value = imagesApi.getFileUrl(uploaded.id, novelId)
    } finally { if (operation === backgroundOperation) backgroundUploading.value = false }
  }

  function clearBackground(novelId: number) {
    assertBackgroundContext(novelId)
    localStorage.setItem(`mapBackground_${novelId}`, 'none')
    backgroundOperation++; backgroundUploading.value = false
    backgroundImageUrl.value = null
    backgroundImageId.value = null
  }

  function $reset() {
    backgroundOperation++; backgroundUploading.value = false
    loading.value = false
    locations.value = []
    backgroundImageId.value = null
    backgroundImageUrl.value = null
  }

  return {
    locations, loading, backgroundImageId, backgroundImageUrl, backgroundUploading,
    fetchLocations, fetchLocationsByType, createLocation, updateLocation, deleteLocation, setLocationTags,
    loadBackgroundFromStorage, setBackground, clearBackground, $reset
  }
})
