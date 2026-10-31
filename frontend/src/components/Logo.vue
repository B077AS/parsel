<script setup lang="ts">
// The app icon (docs/icon/parsel.svg), painted with the theme: the tile and the flap are the accent. The envelope is always white,
// also in dark mode (where the colour used on the accent elsewhere is dark). The icon file keeps the fixed brand blue.
withDefaults(defineProps<{ size?: number }>(), { size: 24 });
</script>

<template>
  <svg class="logo" :width="size" :height="size" viewBox="0 0 16 16" aria-hidden="true">
    <rect width="16" height="16" rx="4" fill="var(--accent)" />
    <rect x="5" y="3" width="6" height="3" rx="1" fill="color-mix(in srgb, #fff 70%, var(--accent))" />
    <rect x="3" y="5" width="10" height="7" rx="1.5" fill="#fff" />
    <path d="M3 5 L8 7.5 L13 5 V7 L8 9.5 L3 7 Z" fill="var(--accent)" />
  </svg>
</template>

<style scoped>
.logo {
  display: block;
  flex: none;
}
</style>
