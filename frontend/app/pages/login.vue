<script setup lang="ts">
import type { FormError, FormSubmitEvent } from '@nuxt/ui'

const auth = useAuth()

if (auth.isLoggedIn.value) {
  await navigateTo('/', { replace: true })
}

const state = reactive({
  username: '',
  password: ''
})

type Schema = typeof state

const loading = ref(false)
const apiError = ref('')

function validate(state: Schema): FormError[] {
  const errors = []
  if (!state.username) errors.push({ name: 'username', message: 'Required' })
  if (!state.password) errors.push({ name: 'password', message: 'Required' })
  return errors
}

async function onSubmit(event: FormSubmitEvent<Schema>) {
  loading.value = true
  apiError.value = ''
  try {
    await auth.login(event.data.username, event.data.password)
    await navigateTo('/', { replace: true })
  } catch (e: any) {
    apiError.value = e?.data?.message || e?.message || 'Login failed'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex items-center justify-center min-h-[calc(100vh-8rem)]">
    <UCard class="w-full max-w-sm">
      <template #header>
        <div class="text-center">
          <h1 class="text-xl font-bold">LocFi</h1>
          <p class="text-sm text-muted mt-1">Sign in to your account</p>
        </div>
      </template>

      <UAlert
        v-if="apiError"
        color="error"
        variant="soft"
        icon="i-lucide-circle-x"
        :title="apiError"
        class="mb-4"
        close
        @update:open="apiError = ''"
      />

      <UForm
        :state="state"
        :validate="validate"
        class="space-y-4"
        @submit="onSubmit"
      >
        <UFormField label="Username" name="username" required>
          <UInput
            v-model="state.username"
            placeholder="Enter your username"
            class="w-full"
          />
        </UFormField>

        <UFormField label="Password" name="password" required>
          <UInput
            v-model="state.password"
            type="password"
            placeholder="Enter your password"
            class="w-full"
          />
        </UFormField>

        <UButton
          type="submit"
          block
          :loading="loading"
        >
          Sign in
        </UButton>
      </UForm>

      <template #footer>
        <p class="text-xs text-center text-muted">
          WiFi Intelligence
        </p>
      </template>
    </UCard>
  </div>
</template>
