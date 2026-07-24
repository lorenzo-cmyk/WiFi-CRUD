<script setup lang="ts">
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

export interface Measurement {
  id: number
  device_id: string
  timestamp: number
  gps_lat: number
  gps_lon: number
  created_at: number
}

const props = defineProps<{
  measurements: Measurement[]
}>()

const mapContainer = ref<HTMLDivElement>()
let map: L.Map | null = null
let markers: L.Marker[] = []

const icon = L.divIcon({
  html: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="size-6 text-primary"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="3" fill="currentColor"/></svg>`,
  className: 'bg-transparent !w-6 !h-6',
  iconAnchor: [12, 12]
})

function updateMarkers() {
  markers.forEach(m => m.remove())
  markers = []

  const measurements = props.measurements
  if (!measurements?.length || !map) return

  const bounds = L.latLngBounds([])

  for (const m of measurements) {
    if (!m.gps_lat || !m.gps_lon) continue
    const marker = L.marker([m.gps_lat, m.gps_lon], { icon })
      .addTo(map)
      .bindPopup(`Device: ${m.device_id?.slice(0, 8)}…<br>Time: ${new Date(m.timestamp * 1000).toLocaleString()}`)
    markers.push(marker)
    bounds.extend([m.gps_lat, m.gps_lon])
  }

  if (markers.length > 0) {
    map.fitBounds(bounds, { padding: [40, 40] })
  }
}

onMounted(() => {
  map = L.map(mapContainer.value!, {
    zoomControl: true,
    attributionControl: false
  }).setView([41.9, 12.5], 6)

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19
  }).addTo(map)

  updateMarkers()
})

watch(() => props.measurements, updateMarkers)

onUnmounted(() => {
  markers.forEach(m => m.remove())
  markers = []
  map?.remove()
  map = null
})
</script>

<template>
  <div ref="mapContainer" class="w-full h-full rounded-lg overflow-hidden" />
</template>
