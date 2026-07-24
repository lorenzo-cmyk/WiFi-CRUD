<script setup lang="ts">
const auth = useAuth()

useHead({
  meta: [
    { name: 'viewport', content: 'width=device-width, initial-scale=1' }
  ],
  link: [
    { rel: 'icon', href: '/favicon.ico' }
  ],
  htmlAttrs: {
    lang: 'en'
  }
})

useSeoMeta({
  title: 'LocFi',
  description: 'WiFi Intelligence'
})
</script>

<template>
  <UApp>
    <template v-if="auth.isLoggedIn.value">
      <UHeader>
        <template #left>
          <NuxtLink to="/" class="font-bold text-lg">
          LocFi
          </NuxtLink>
        </template>

        <template #right>
          <UColorModeButton />
          <UButton
            color="neutral"
            variant="ghost"
            icon="i-lucide-log-out"
            aria-label="Sign out"
            @click="auth.logout()"
          />
        </template>
      </UHeader>
    </template>

    <main class="flex-1 min-h-0">
      <NuxtPage />
    </main>

    <UFooter v-if="auth.isLoggedIn.value">
      <template #left>
        <p class="text-sm text-muted">
          LocFi &bull; WiFi Intelligence
        </p>
      </template>
    </UFooter>
  </UApp>
</template>
