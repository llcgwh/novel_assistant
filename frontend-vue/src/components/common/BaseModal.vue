<template>
  <Teleport to="body">
    <div class="modal" @click.self="!busy && $emit('close')">
      <div class="modal-content" :aria-busy="busy">
        <h2>{{ title }}</h2>
        <fieldset class="modal-fields" :disabled="busy">
          <slot></slot>
        </fieldset>
        <div class="modal-actions">
          <slot name="actions" :busy="busy">
            <button class="btn-secondary" :disabled="busy" @click="!busy && $emit('close')">取消</button>
            <button class="btn-primary" :disabled="busy" @click="!busy && $emit('confirm')">{{ busy ? '保存中…' : '确定' }}</button>
          </slot>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  busy?: boolean
}>(), { busy: false })

defineEmits<{
  close: []
  confirm: []
}>()
</script>

<style scoped>
.modal-fields {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.modal-fields:disabled {
  pointer-events: none;
}
</style>
