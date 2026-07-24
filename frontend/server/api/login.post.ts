export default defineEventHandler(async (event) => {
  const { backendUrl } = useRuntimeConfig(event)
  const body = await readBody(event)
  const res = await $fetch<{ session_token: string; expires_at: number }>(`${backendUrl}/api/users/login`, {
    method: 'POST',
    body,
    headers: { 'Content-Type': 'application/json' }
  })
  return res
})
