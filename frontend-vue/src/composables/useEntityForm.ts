import { ref, reactive } from 'vue'

export function useEntityForm<T extends Record<string, any>>(defaults: T) {
  const isEditing = ref(false)
  const editingId = ref<number | null>(null)
  const formData = reactive({ ...defaults })

  function startEdit(entity: T & { id: number }) {
    isEditing.value = true
    editingId.value = entity.id
    Object.keys(defaults).forEach(key => {
      (formData as any)[key] = entity[key] ?? (defaults as any)[key]
    })
  }

  function resetForm() {
    isEditing.value = false
    editingId.value = null
    Object.keys(defaults).forEach(key => {
      (formData as any)[key] = (defaults as any)[key]
    })
  }

  return { isEditing, editingId, formData, startEdit, resetForm }
}
