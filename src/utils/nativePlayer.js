// Android native playback bridge (Phase 5).
// React owns pct/watched/history; PlayerActivity owns the resume position while
// playing. We only copy the native result back into the existing structures.

import { platform } from "../platform";

export const ANDROID_ANIME_ONLY =
  "Playback on Android is currently available for anime only. Movies and non-anime shows can be watched on desktop.";

/**
 * Opens PlayerActivity and resolves once it closes.
 * @returns {Promise<null|{pct:number, seconds:number, watched:boolean}>}
 */
export async function startNativePlayback({
  mediaId,
  watchedThreshold = 20,
  ...stream
}) {
  if (!platform.player) throw new Error("Native player unavailable");

  const saved = await platform.player.getProgress(mediaId).catch(() => null);
  const startPosition =
    saved && !saved.completed && saved.position > 0 ? saved.position : 0;

  await platform.player.open({ ...stream, mediaId, startPosition });

  const progress = await platform.player.getProgress(mediaId).catch(() => null);
  if (!progress || !(progress.duration > 0)) return null;
  const position = Math.max(0, progress.position || 0);
  return {
    pct: Math.min(100, Math.floor((position / progress.duration) * 100)),
    seconds: Math.floor(position),
    watched:
      !!progress.completed ||
      progress.duration - position <= watchedThreshold,
  };
}
