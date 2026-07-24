<script setup lang="ts">
import type { Measurement } from '~/components/MapDisplay.vue'

const auth = useAuth()

if (!auth.isLoggedIn.value) {
  await navigateTo('/login', { replace: true })
}

const timeRange = ref('7d')
const deviceId = ref('')
const bssid = ref('')

const startTime = computed(() => {
  const now = Math.floor(Date.now() / 1000)
  switch (timeRange.value) {
    case '1h': return now - 3600
    case '1d': return now - 86400
    case '7d': return now - 604800
    case '30d': return now - 2592000
    default: return undefined
  }
})

const query = computed(() => {
  const q: Record<string, any> = { start_time: startTime.value, limit: 1000 }
  if (deviceId.value) q.device_id = deviceId.value
  if (bssid.value) q.bssid = bssid.value
  return q
})

const { data } = await useFetch<{ measurements: Measurement[] }>('/api/measurements', {
  headers: { 'Authorization': `Bearer ${auth.sessionToken.value}` },
  query
})

const measurements = computed(() => data.value?.measurements ?? [])

const { data: devicesData } = await useFetch<Array<{ id: string; name: string }>>('/api/devices', {
  headers: { 'Authorization': `Bearer ${auth.sessionToken.value}` }
})
const devices = computed(() => devicesData.value ?? [])

const TIME_OPTIONS = [
  { value: '1h', label: '1h' },
  { value: '1d', label: '1d' },
  { value: '7d', label: '7d' },
  { value: '30d', label: '30d' }
]
</script>

<template>
  <div class="h-[calc(100dvh-7rem)] relative">
    <ClientOnly>
      <MapDisplay :measurements="measurements" class="absolute inset-0" />
    </ClientOnly>

    <div class="absolute top-4 right-4 z-[1000] w-56 space-y-2">
      <UCard class="shadow-lg">
        <div class="space-y-3">
          <div>
            <p class="text-xs text-muted mb-1.5">Period</p>
            <div class="flex gap-1">
              <UButton
                v-for="opt in TIME_OPTIONS"
                :key="opt.value"
                size="xs"
                :variant="timeRange === opt.value ? 'solid' : 'outline'"
                :color="timeRange === opt.value ? 'primary' : 'neutral'"
                @click="timeRange = opt.value"
              >
                {{ opt.label }}
              </UButton>
            </div>
          </div>

          <div>
            <p class="text-xs text-muted mb-1.5">Device</p>
            <select
              v-model="deviceId"
              class="w-full rounded-md border-0 text-xs px-2 py-1.5 bg-default ring ring-inset ring-accented text-highlighted"
            >
              <option value="">All devices</option>
              <option v-for="d in devices" :key="d.id" :value="d.id">
                {{ d.name || d.id.slice(0, 8) }}
              </option>
            </select>
          </div>

          <div>
            <p class="text-xs text-muted mb-1.5">BSSID</p>
            <input
              v-model="bssid"
              placeholder="e.g. ac:2a:a1:90:e0:2e"
              class="w-full rounded-md border-0 text-xs px-2 py-1.5 bg-default ring ring-inset ring-accented text-highlighted placeholder:text-dimmed"
            />
          </div>

          <hr class="border-default" />

          <div class="text-xs text-muted">
            <ClientOnly>
              {{ measurements.length }} points
            </ClientOnly>
          </div>

          <hr class="border-default" />

          <div class="space-y-1">
            <p class="text-xs text-muted mb-1">Legend</p>
            <div class="flex items-center gap-1.5 text-xs"><span class="inline-block w-2 h-2 rounded-full shrink-0" style="background:#0066CC" />TIM</div>
            <div class="flex items-center gap-1.5 text-xs"><span class="inline-block w-2 h-2 rounded-full shrink-0" style="background:#F97316" />Wind3</div>
            <div class="flex items-center gap-1.5 text-xs"><span class="inline-block w-2 h-2 rounded-full shrink-0" style="background:#EAB308" />Vodafone/Fastweb</div>
            <div class="flex items-center gap-1.5 text-xs"><span class="inline-block w-2 h-2 rounded-full shrink-0" style="background:#78350F" />Iliad</div>
            <div class="flex items-center gap-1.5 text-xs"><span class="inline-block w-2 h-2 rounded-full shrink-0" style="background:#6B7280" />Other</div>
          </div>
        </div>
      </UCard>
    </div>
  </div>
</template>
