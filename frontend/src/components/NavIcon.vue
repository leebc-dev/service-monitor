<script setup lang="ts">
defineProps<{ name: string }>();
const paths: Record<string, string> = {
  overview: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  endpoints: "M8 5H5v14h3 M16 5h3v14h-3 M13 8l-2 8",
  requests: "M8 5h13 M8 12h13 M8 19h13 M3 5h.01 M3 12h.01 M3 19h.01",
  alerts: "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4",
  infrastructure: "M3 3h18v6H3z M3 15h18v6H3z M7 6h.01 M7 18h.01 M12 9v6",
};
</script>
<template>
  <svg
    viewBox="0 0 24 24"
    width="18"
    height="18"
    fill="none"
    stroke="currentColor"
    stroke-width="1.5"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] ?? paths.overview" />
  </svg>
</template>
