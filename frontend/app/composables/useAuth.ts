interface AuthState {
  session_token: string
  expires_at: number
}

export const useAuth = () => {
  const cookie = useCookie<AuthState | null>('auth', {
    maxAge: 60 * 60 * 24 * 7,
    sameSite: 'lax',
    secure: !import.meta.dev,
    default: () => null
  })

  const isLoggedIn = computed(() => {
    if (!cookie.value) return false
    return cookie.value.expires_at > Math.floor(Date.now() / 1000)
  })

  const sessionToken = computed(() => cookie.value?.session_token ?? null)

  async function login(username: string, password: string) {
    const res = await $fetch<{ session_token: string; expires_at: number }>('/api/login', {
      method: 'POST',
      body: { username, password }
    })
    cookie.value = { session_token: res.session_token, expires_at: res.expires_at }
  }

  function logout() {
    cookie.value = null
  }

  return { login, logout, isLoggedIn, sessionToken }
}
