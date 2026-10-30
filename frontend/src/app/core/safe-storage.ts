/**
 * Browser storage that never throws (private windows, blocked site data, previews).
 * Values are only conveniences: the app must work when they come back empty.
 */
function read(storage: () => Storage, key: string): string | null {
  try {
    return storage().getItem(key);
  } catch {
    return null;
  }
}

function write(storage: () => Storage, key: string, value: string | null): void {
  try {
    if (value === null) {
      storage().removeItem(key);
    } else {
      storage().setItem(key, value);
    }
  } catch {
    // Ignore: storage is optional.
  }
}

export const sessionStore = {
  get: (key: string) => read(() => sessionStorage, key),
  set: (key: string, value: string | null) => write(() => sessionStorage, key, value)
};

export const localStore = {
  get: (key: string) => read(() => localStorage, key),
  set: (key: string, value: string | null) => write(() => localStorage, key, value)
};
