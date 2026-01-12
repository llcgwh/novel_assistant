<template>
  <Teleport to="body">
    <div class="modal" @click.self="$emit('close')">
      <div class="modal-content">
        <h2>{{ title }}</h2>
        <slot></slot>
        <div class="modal-actions">
          <slot name="actions">
            <button class="btn-secondary" @click="$emit('close')">取消</button>
            <button class="btn-primary" @click="$emit('confirm')">确定</button>
          </slot>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
defineProps<{
  title: string
}>()

defineEmits<{
  close: []
  confirm: []
}>()
</script>
