import { reactive } from 'vue';
import { api, type VaultStatus } from './api';

// Where passwords live. Normally the operating system's keystore, which needs nothing from the user. On a machine
// without one (a Linux desktop with no Secret Service) Parsel keeps them in an encrypted file, and the user types a
// master password once per launch; this is the state for that.

export const vault = reactive({
  loaded: false,
  mode: 'os' as VaultStatus['mode'],
  state: 'ready' as VaultStatus['state'],
  reason: '',
  busy: false,
  error: '',
});

function apply(status: VaultStatus): void {
  vault.mode = status.mode;
  vault.state = status.state;
  vault.reason = status.reason ?? '';
}

export async function refreshVault(): Promise<void> {
  try {
    apply(await api.vaultStatus());
  } catch (e) {
    vault.error = e instanceof Error ? e.message : String(e);
  } finally {
    vault.loaded = true;
  }
}

/** Creates the vault (state "new") or unlocks it (state "locked") with the password. */
export async function submitVaultPassword(password: string): Promise<boolean> {
  vault.busy = true;
  vault.error = '';
  try {
    apply(vault.state === 'new' ? await api.createVault(password) : await api.unlockVault(password));
    return true;
  } catch (e) {
    vault.error = e instanceof Error ? e.message : String(e);
    return false;
  } finally {
    vault.busy = false;
  }
}
