<template>
  <svg
    class="studio-icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.65"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.spark" />
  </svg>
</template>
<script setup lang="ts">
defineProps<{ name: string }>()
const paths: Record<string, string> = {
  book: 'M4 4h7a3 3 0 0 1 3 3v14a4 4 0 0 0-4-2H4V4Zm10 3a3 3 0 0 1 3-3h3v15h-3a3 3 0 0 0-3 2M7 8h3M7 12h3',
  overview: 'M3 3h7v7H3zM14 3h7v4h-7zM14 11h7v10h-7zM3 14h7v7H3z',
  outlines: 'M8 4h12M8 10h12M8 16h8M3 4h.01M3 10h.01M3 16h.01M18 19l2 2 3-4',
  timeline: 'M6 3v18M3 6h6M3 16h6M13 5h7M13 9h4M13 15h7M13 19h4',
  foreshadows:
    'M12 3a7 7 0 0 0-4 13v3h8v-3a7 7 0 0 0-4-13ZM9 22h6M10 10l2 2 2-2M12 12v4',
  characters: 'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM4 21v-2a8 8 0 0 1 16 0v2',
  scenes: 'M3 5h18v15H3zM3 10h18M7 5l3 5M14 5l3 5M9 15h6',
  worldview:
    'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM3 12h18M12 3a18 18 0 0 1 0 18 18 18 0 0 1 0-18Z',
  map: 'M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3V6ZM9 3v15M15 6v15',
  relationships:
    'M14 5a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM7 19a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM21 19a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM11 7l-5 10M13 7l5 10M7 19h10',
  tags: 'M3 3h8l10 10-8 8L3 11V3ZM7 7h.01',
  settings:
    'M11 7a3 3 0 1 0 6 0 3 3 0 0 0-6 0ZM4 7h7M17 7h3M7 17a3 3 0 1 0 6 0 3 3 0 0 0-6 0ZM4 17h3M13 17h7',
  search: 'M18 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0ZM16 16l5 5',
  plus: 'M12 5v14M5 12h14',
  arrow: 'M5 12h14M13 6l6 6-6 6',
  moon: 'M20 15A9 9 0 0 1 9 4a9 9 0 1 0 11 11Z',
  sun: 'M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM12 2v2M12 20v2M2 12h2M20 12h2M5 5l1 1M18 18l1 1M5 19l1-1M18 6l1-1',
  focus: 'M8 3H3v5M16 3h5v5M3 16v5h5M21 16v5h-5M8 12h8',
  close: 'M6 6l12 12M6 18L18 6',
  spark: 'M12 2l3 7 7 3-7 3-3 7-3-7-7-3 7-3 3-7Z',
  download: 'M12 3v12M7 10l5 5 5-5M4 17v4h16v-4',
  timer:
    'M9 2h6M12 5v2M19 5l2 2M20 14a8 8 0 1 1-16 0 8 8 0 0 1 16 0ZM12 10v4l3 2',
  pen: 'M16 3l5 5-12 12-6 1 1-6L16 3ZM13 6l5 5',
  check: 'M5 12l4 4L19 6',
  chevron: 'M9 5l7 7-7 7',
}
</script>
