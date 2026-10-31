<script setup lang="ts">
import { mdiShieldLockOutline } from '@mdi/js';
import { computed, ref } from 'vue';
import { submitVaultPassword, vault } from '../vault';
import Icon from './Icon.vue';

// Shown instead of the app on a machine with no system keychain, until the master password has been given.

const password = ref('');
const confirm = ref('');
const isNew = computed(() => vault.state === 'new');
const mismatch = computed(() => isNew.value && confirm.value !== '' && confirm.value !== password.value);
const canSubmit = computed(() => !vault.busy && password.value.length > 0 && (!isNew.value || confirm.value === password.value));

async function submit(): Promise<void> {
  if (!canSubmit.value) return;
  await submitVaultPassword(password.value);
  password.value = '';
  confirm.value = '';
}
</script>

<template>
  <div class="gate">
    <form class="card" @submit.prevent="submit">
      <Icon :path="mdiShieldLockOutline" :size="36" class="icon" />
      <h1>{{ isNew ? 'Choose a master password' : 'Unlock Parsel' }}</h1>
      <p v-if="isNew">
        Parsel could not find a keychain on this computer<template v-if="vault.reason"> ({{ vault.reason }})</template>, so it keeps your mail passwords in an
        encrypted file. This password protects that file. It is not stored anywhere: if you forget it, you will have to add your accounts again.
      </p>
      <p v-else>Enter your master password to unlock your saved mail passwords.</p>

      <label class="field">
        <span>{{ isNew ? 'Master password' : 'Password' }}</span>
        <input v-model="password" type="password" autocomplete="off" autofocus />
      </label>
      <label v-if="isNew" class="field">
        <span>Repeat it</span>
        <input v-model="confirm" type="password" autocomplete="off" />
        <small v-if="mismatch" class="bad">The two passwords are different.</small>
        <small v-else class="muted">A long phrase is better than a short word. Anyone with the file can try to guess it.</small>
      </label>

      <p v-if="vault.error" class="bad">{{ vault.error }}</p>
      <button class="btn primary" type="submit" :disabled="!canSubmit">{{ isNew ? 'Create vault' : 'Unlock' }}</button>
    </form>
  </div>
</template>

<style scoped>
.gate {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 24px;
  background: var(--bg);
}
.card {
  width: 100%;
  max-width: 440px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.icon {
  color: var(--accent);
}
h1 {
  margin: 0;
  font-size: 22px;
}
p {
  margin: 0;
  color: var(--text-2);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.field span {
  font-weight: 550;
  font-size: 12.5px;
}
.field input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  background: var(--bg-raised);
  font: inherit;
  color: var(--text);
}
.field input:focus {
  outline: 2px solid var(--accent);
  outline-offset: -1px;
  border-color: var(--accent);
}
small {
  color: var(--text-3);
}
.bad {
  color: var(--danger);
}
.btn {
  align-self: flex-start;
}
</style>
