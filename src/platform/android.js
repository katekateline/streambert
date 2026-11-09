// Android implementation of the platform contract.
// SecureStorage is a small native Capacitor plugin backed by Android Keystore.

import { registerPlugin } from "@capacitor/core";

const NativeSecureStorage = registerPlugin("SecureStorage");
const NativeHiAnime = registerPlugin("HiAnime");

export const androidPlatform = {
  kind: "android",
  isElectron: false,
  isAndroid: true,
  secureStorage: {
    async get(key) {
      try {
        const { value } = await NativeSecureStorage.get({ key });
        return value ?? null;
      } catch {
        return null;
      }
    },
    async set(key, value) {
      try {
        if (value === null || value === undefined || value === "") {
          await NativeSecureStorage.remove({ key });
          return;
        }
        await NativeSecureStorage.set({ key, value: String(value) });
      } catch {}
    },
    async remove(key) {
      try {
        await NativeSecureStorage.remove({ key });
      } catch {}
    },
  },
  async openExternal(url) {
    if (!/^https?:\/\//i.test(url || "")) return;
    // Capacitor hands http(s) links that leave the app to the system browser.
    const a = document.createElement("a");
    a.href = url;
    a.target = "_blank";
    a.rel = "noopener noreferrer";
    document.body.appendChild(a);
    a.click();
    a.remove();
  },
  getPlatform() {
    return Promise.resolve("android");
  },
  resolveHiAnime(args) {
    return NativeHiAnime.resolve(args);
  },
  onHiAnimeProgress(cb) {
    const pending = NativeHiAnime.addListener("progress", (event) => cb(event.stage));
    return () => {
      pending.then((handle) => handle.remove()).catch(() => {});
    };
  },
};
