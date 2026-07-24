<script setup lang="ts">
import type { Measurement } from '~/components/MapDisplay.vue'

const auth = useAuth()

if (!auth.isLoggedIn.value) {
  await navigateTo('/login', { replace: true })
}

const measurements = ref<Measurement[]>([])
const loading = ref(true)
const fetchError = ref<string | null>(null)

try {
  const res = await $fetch<{ measurements: Measurement[] }>('/api/measurements', {
    headers: {
      'Authorization': `Bearer ${auth.sessionToken.value}`
    }
  })
  measurements.value = res.measurements
} catch (e: any) {
  fetchError.value = e?.message || 'Failed to fetch measurements'
} finally {
  loading.value = false
}
</script>

<template>
  <div class="h-[calc(100dvh-7rem)]">
    <div v-if="loading" class="h-full flex items-center justify-center text-muted">
      Loading…
    </div>
    <div v-else-if="fetchError" class="h-full flex items-center justify-center text-error">
      {{ fetchError }}
    </div>
    <ClientOnly v-else>
      <MapDisplay :measurements="measurements" class="h-full w-full rounded-lg overflow-hidden" />
    </ClientOnly>
  </div>
</template>
