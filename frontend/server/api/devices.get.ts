export default defineEventHandler(async (event) => {
  const { backendUrl } = useRuntimeConfig(event)
  const token = getHeader(event, 'authorization')

  if (!token) {
    throw createError({ statusCode: 401, message: 'Missing Authorization header' })
  }

  const res = await $fetch<Array<{ id: string; name: string }>>(`${backendUrl}/api/devices`, {
    headers: { 'Authorization': token, 'Content-Type': 'application/json' }
  })
  return res
})
