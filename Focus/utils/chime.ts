/**
 * Completion chime for focus sessions.
 *
 * Settings → Focus → "Completion sound" (timerSettings.notificationEnabled) had
 * no consumer anywhere, so the toggle did nothing. Web Audio needs a user
 * gesture in some WebViews, so every failure path is swallowed — a missing chime
 * must never break session completion.
 */
export function playCompletionChime(): void {
  try {
    if (typeof window === 'undefined') return
    const Ctx = window.AudioContext || (window as any).webkitAudioContext
    if (!Ctx) return
    const ctx: AudioContext = new Ctx()
    const now = ctx.currentTime
    // Two-tone rising chime (E5 → A5).
    const tones = [
      { freq: 659.25, start: 0, dur: 0.18 },
      { freq: 880, start: 0.2, dur: 0.28 },
    ]
    for (const t of tones) {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sine'
      osc.frequency.value = t.freq
      gain.gain.setValueAtTime(0.0001, now + t.start)
      gain.gain.exponentialRampToValueAtTime(0.18, now + t.start + 0.02)
      gain.gain.exponentialRampToValueAtTime(0.0001, now + t.start + t.dur)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start(now + t.start)
      osc.stop(now + t.start + t.dur + 0.02)
    }
    const closeAt = now + 0.6
    setTimeout(() => { void ctx.close().catch(() => {}) }, Math.max(0, (closeAt - now) * 1000))
  } catch {
    /* audio unavailable / autoplay blocked — non-fatal */
  }
}
