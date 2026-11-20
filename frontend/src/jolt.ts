// The typed bridge to the Java side. This is the only file that knows about window.jolt.
//
// Jolt (0.5.0) injects `window.jolt` into every page it shows, with request/response (`call`) and pushed events (`onEvent`) built in.
// The handlers are registered in Java (Handlers.java, App.java); `api.ts` has one typed function per handler.

/** What a failed call rejects with: Jolt's `JoltRpcError`, an Error that also carries the code Java chose (see ParselRpc.java). */
export interface RpcError extends Error {
  name: 'JoltRpcError';
  /** `problem`: the message is a sentence for the user. `wrong_password`, `vault_locked`: the same, for the vault. Jolt's own codes:
   *  `internal` (a bug in Parsel), `bad_params`, `unknown_method`, `unavailable`, `too_many_calls`, `too_large`, `timeout`. */
  code: string;
  data: unknown;
}

declare global {
  interface Window {
    jolt?: {
      call?<T>(method: string, params?: unknown, options?: { signal?: AbortSignal; timeout?: number; onProgress?: (data: unknown) => void }): Promise<T>;
      /** Returns a function that removes the listener. */
      onEvent?(name: string, callback: (data: unknown) => void): () => void;
      /** Asks Java for another window showing an address of this app; false when it refused (see App.java). */
      openWindow?(path: string, options?: { key?: string; title?: string; width?: number; height?: number }): Promise<boolean>;
    };
  }
}

/** Calls a Java handler registered with `Rpc.on(method, ...)` and resolves with its result. */
export function call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  const jolt = window.jolt;
  if (!jolt?.call) {
    return Promise.reject(new Error('Not running inside a Jolt window, so there is no Java side to call.'));
  }
  return jolt.call<T>(method, params);
}

/** Subscribes to an event pushed by Java with `Rpc.emit(name, data)`. Returns an unsubscribe function. */
export function onEvent<T>(name: string, callback: (data: T) => void): () => void {
  const jolt = window.jolt;
  if (!jolt?.onEvent) return () => {};
  return jolt.onEvent(name, (data) => callback(data as T));
}

/** Opens another window on an address of this app. Java decides whether to; resolves false if it refused or cannot. */
export async function openWindow(path: string, options: { key?: string; title?: string; width?: number; height?: number }): Promise<boolean> {
  const open = window.jolt?.openWindow;
  if (!open) return false;
  return open.call(window.jolt, path, options);
}

/** The codes whose message Java wrote for the user (`UserFacingException` and the vault's), so it can be shown as it is. */
const FOR_THE_USER = new Set(['problem', 'wrong_password', 'vault_locked']);

/**
 * The text to show for a failed call. A sentence Java wrote for the user goes through; anything else (a bug, a bad request, a lost
 * window) gets one plain sentence, because its own message is for the log. While developing (`npm run dev`) the real message is shown.
 */
export function errorText(error: unknown): string {
  if (error instanceof Error) {
    const code = (error as Partial<RpcError>).code;
    if (code === undefined || FOR_THE_USER.has(code) || import.meta.env.DEV) return error.message;
    return 'Something went wrong inside Parsel. Please try again.';
  }
  return String(error);
}
