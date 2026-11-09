// Single platform entry point. Components import this instead of sniffing
// window.electron or Capacitor themselves.
//
// Contract:
//   kind: "electron" | "android" | "web"
//   isElectron, isAndroid
//   secureStorage.get(key) -> Promise<string|null>
//   secureStorage.set(key, value) -> Promise<void>  (null/"" deletes)
//   secureStorage.remove(key) -> Promise<void>
//   openExternal(url) -> Promise<void>
//   getPlatform() -> Promise<string|null>

import { Capacitor } from "@capacitor/core";
import { electronPlatform } from "./electron";
import { androidPlatform } from "./android";

const webPlatform = {
  kind: "web",
  isElectron: false,
  isAndroid: false,
  secureStorage: {
    async get() {
      return null;
    },
    async set() {},
    async remove() {},
  },
  async openExternal(url) {
    if (/^https?:\/\//i.test(url || "")) window.open(url, "_blank", "noopener");
  },
  getPlatform() {
    return Promise.resolve("web");
  },
};

const isElectron = typeof window !== "undefined" && !!window.electron;
const isAndroid = !isElectron && Capacitor.getPlatform() === "android";

export const platform = isElectron
  ? electronPlatform
  : isAndroid
    ? androidPlatform
    : webPlatform;

if (typeof document !== "undefined") {
  document.documentElement.setAttribute("data-platform", platform.kind);
}
