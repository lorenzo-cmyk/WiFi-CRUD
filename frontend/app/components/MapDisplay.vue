<script setup lang="ts">
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import 'leaflet.markercluster'
import 'leaflet.markercluster/dist/MarkerCluster.css'
import 'leaflet.markercluster/dist/MarkerCluster.Default.css'

export interface WifiScan {
  ssid: string
  bssid: string
  rssi: number
}

export interface Measurement {
  id: number
  device_id: string
  timestamp: number
  gps_lat: number
  gps_lon: number
  created_at: number
  wifi_scans: WifiScan[]
}

const props = defineProps<{
  measurements: Measurement[]
}>()

const emit = defineEmits<{
  visibleStats: [stats: Record<string, number>]
}>()

const mapContainer = ref<HTMLDivElement>()
let map: L.Map | null = null
let clusterGroup: L.MarkerClusterGroup | null = null

const OPERATORS = [
  { id: 'tim', name: 'TIM', color: '#0066CC', match: (s: string) => /^tim[- ]|telecom|alice/.test(s) },
  { id: 'wind3', name: 'Wind3', color: '#F97316', match: (s: string) => /wind3|windtre|^3[- ]|^wind[- ]|^win(d|dt)/i.test(s) },
  { id: 'vodafone', name: 'Vodafone/Fastweb', color: '#EAB308', match: (s: string) => /vodafone|fastweb/.test(s) },
  { id: 'iliad', name: 'Iliad', color: '#78350F', match: (s: string) => /iliad|^free[- ]/i.test(s) }
]

const OTHER = { name: 'Other', color: '#6B7280' }

function groupScans(scans: WifiScan[]): { name: string; color: string; networks: WifiScan[] }[] {
  const groups = [
    ...OPERATORS.map(op => ({ name: op.name, color: op.color, networks: [] as WifiScan[] })),
    { name: OTHER.name, color: OTHER.color, networks: [] as WifiScan[] }
  ]
  const otherIdx = groups.length - 1
  for (const w of scans) {
    const ssid = w.ssid.replace(/^"|"$/g, '').toLowerCase()
    const match = OPERATORS.findIndex(op => op.match(ssid))
    groups[match >= 0 ? match : otherIdx]!.networks.push(w)
  }
  return groups
}

function dominantColor(scans: WifiScan[]): string {
  if (!scans?.length) return '#6B7280'
  const groups = groupScans(scans)
  let best = groups[0]!
  for (let i = 1; i < groups.length; i++) {
    if (groups[i]!.networks.length > best.networks.length) best = groups[i]!
  }
  return best.networks.length > 0 ? best.color : '#6B7280'
}

function makeIcon(color: string) {
  return L.divIcon({
    html: `<svg viewBox="0 0 28 28" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="14" cy="14" r="11" fill="${color}" stroke="#fff" stroke-width="2"/><circle cx="14" cy="14" r="4" fill="#fff"/></svg>`,
    className: 'bg-transparent !w-7 !h-7',
    iconAnchor: [14, 14]
  })
}

function popupContent(m: Measurement): string {
  const time = new Date(m.timestamp * 1000).toLocaleString()
  const device = m.device_id?.slice(0, 8)
  let html = `<div class="text-sm font-semibold mb-0.5" style="color:#4A148C">${device}…</div>`
  html += `<div class="text-xs text-gray-500 mb-2">${time}</div>`
  if (!m.wifi_scans?.length) {
    html += `<div class="text-xs text-gray-400">No WiFi data</div>`
    return html
  }

  const groups = groupScans(m.wifi_scans)
  for (const g of groups) {
    if (!g.networks.length) continue
    html += sectionHtml(g.name, g.color, g.networks)
  }

  html += `<div class="text-xs text-gray-400 mt-1.5">${m.wifi_scans.length} total networks</div>`
  return html
}

function sectionHtml(name: string, color: string, networks: WifiScan[]): string {
  let html = `<div class="mt-1.5 first:mt-0">`
  html += `<div class="flex items-center gap-1.5 mb-1"><span class="inline-block w-2 h-2 rounded-full shrink-0" style="background:${color}"></span><span class="text-xs font-semibold" style="color:${color}">${name}</span><span class="text-xs text-gray-400">(${networks.length})</span></div>`
  html += `<div class="space-y-0.5">`
  for (const w of networks) {
    const ssid = w.ssid.replace(/^"|"$/g, '') || 'hidden'
    html += `<div class="text-xs pl-3.5"><div class="flex items-center justify-between gap-2"><span class="truncate max-w-36">${ssid}</span><span class="font-mono text-gray-500 shrink-0">${w.rssi} dBm</span></div><div class="font-mono text-[10px] text-gray-400 truncate select-all">${w.bssid}</div></div>`
  }
  html += `</div></div>`
  return html
}

function clusterIcon(cluster: L.MarkerCluster) {
  const childMarkers = cluster.getAllChildMarkers()
  const counts: Record<string, number> = {}
  for (const m of childMarkers) {
    const c = (m as any)._dominantColor || '#6B7280'
    counts[c] = (counts[c] || 0) + 1
  }
  let bestColor = '#6B7280'
  let bestCount = 0
  for (const [c, n] of Object.entries(counts)) {
    if (n > bestCount) { bestCount = n; bestColor = c }
  }
  return L.divIcon({
    html: `<div class="flex items-center justify-center w-full h-full rounded-full font-bold text-sm text-white" style="background:${bestColor}">${cluster.getChildCount()}</div>`,
    className: 'cluster-icon',
    iconSize: L.point(40, 40)
  })
}

function emitVisibleStats() {
  if (!map) return
  const bounds = map.getBounds()
  const matching = props.measurements.filter(m =>
    m.gps_lat && m.gps_lon && bounds.contains([m.gps_lat, m.gps_lon])
  )
  const counts: Record<string, number> = {}
  for (const op of OPERATORS) counts[op.name] = 0
  counts[OTHER.name] = 0
  for (const m of matching) {
    for (const w of m.wifi_scans) {
      const ssid = w.ssid.replace(/^"|"$/g, '').toLowerCase()
      const match = OPERATORS.findIndex(op => op.match(ssid))
      const name = match >= 0 ? OPERATORS[match]!.name : OTHER.name
      counts[name] = (counts[name] || 0) + 1
    }
  }
  emit('visibleStats', counts)
}

function updateMarkers() {
  if (!map) return
  clusterGroup?.clearLayers()

  const measurements = props.measurements
  if (!measurements?.length) return

  const bounds = L.latLngBounds([])
  const markers: L.Marker[] = []

  for (const m of measurements) {
    if (!m.gps_lat || !m.gps_lon) continue
    const color = dominantColor(m.wifi_scans)
    const marker = L.marker([m.gps_lat, m.gps_lon], { icon: makeIcon(color) })
      .bindPopup(popupContent(m), { maxWidth: 340, minWidth: 280 })
    ;(marker as any)._dominantColor = color
    markers.push(marker)
    bounds.extend([m.gps_lat, m.gps_lon])
  }

  clusterGroup = L.markerClusterGroup({
    chunkedLoading: true,
    iconCreateFunction: clusterIcon
  })
  clusterGroup.addLayers(markers)
  map.addLayer(clusterGroup)

  if (markers.length > 0) {
    map!.whenReady(() => map!.fitBounds(bounds, { padding: [40, 40] }))
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
  map.on('moveend', emitVisibleStats)
})

watch(() => props.measurements, () => {
  updateMarkers()
  emitVisibleStats()
})

onUnmounted(() => {
  if (clusterGroup && map) map.removeLayer(clusterGroup)
  map?.remove()
  map = null
  clusterGroup = null
})
</script>

<template>
  <div ref="mapContainer" class="w-full h-full rounded-lg overflow-hidden" />
</template>

<style>
.cluster-icon {
  background: none !important;
  border: none !important;
}
.cluster-icon div {
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(0,0,0,.3);
}
</style>
