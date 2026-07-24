export default defineEventHandler(async (event) => {
  const { backendUrl } = useRuntimeConfig(event)
  const token = getHeader(event, 'authorization')

  if (!token) {
    throw createError({ statusCode: 401, message: 'Missing Authorization header' })
  }

  const query = getQuery(event)
  const params = new URLSearchParams()
  if (query.limit) params.set('limit', String(query.limit))
  if (query.offset) params.set('offset', String(query.offset))
  if (query.device_id) params.set('device_id', String(query.device_id))
  if (query.bssid) params.set('bssid', String(query.bssid))
  if (query.start_time) params.set('start_time', String(query.start_time))
  if (query.end_time) params.set('end_time', String(query.end_time))

  const url = `${backendUrl}/api/measurements${params.toString() ? '?' + params.toString() : ''}`

  try {
    const res = await $fetch(url, {
      headers: {
        'Authorization': token,
        'Content-Type': 'application/json'
      }
    })
    return res
  } catch (e: any) {
    throw createError({
      statusCode: e?.statusCode || 502,
      message: e?.message || 'Backend request failed'
    })
  }
})
