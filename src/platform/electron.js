// Electron implementation of the platform contract.
// Delegates to the existing contextBridge surface from preload.js.

const bridge = () => (typeof window !== "undefined" ? window.electron : null);

export const electronPlatform = {
  kind: "electron",
  isElectron: true,
  isAndroid: false,
  secureStorage: {
    async get(key) {
      return (await bridge()?.secureGet?.(key)) ?? null;
    },
    async set(key, value) {
      await bridge()?.secureSet?.(key, value ?? "");
    },
    async remove(key) {
      await bridge()?.secureSet?.(key, "");
    },
  },
  async openExternal(url) {
    await bridge()?.openExternal?.(url);
  },
  getPlatform() {
    return bridge()?.getPlatform?.() ?? Promise.resolve(null);
  },
  resolveHiAnime(args) {
    return (
      bridge()?.resolveHiAnime?.(args) ??
      Promise.resolve({ ok: false, error: "HiAnime resolver unavailable" })
    );
  },
  onHiAnimeProgress(cb) {
    return bridge()?.onHiAnimeProgress?.(cb) ?? (() => {});
  },
};
