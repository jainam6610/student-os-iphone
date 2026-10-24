/**
 * Generates the bundled alarm tone (`android/app/src/main/res/raw/studentos_alarm.mp3`).
 *
 * Why this exists: the previous asset was a 1 s, 444 Hz beep at -6 dBFS. Phone
 * speakers roll off hard below ~600 Hz and are most efficient from 1.5-3.5 kHz,
 * so that tone was barely audible even with the alarm stream at max.
 *
 * The tone below is deliberately "unignorable":
 *  - alternating 1900 Hz / 2600 Hz bursts (two pitches so the ear can't adapt)
 *  - 3 beeps per burst with short gaps, 4 bursts per loop (~4.1 s)
 *  - a 2nd/3rd harmonic per beep for perceived loudness on tiny speakers
 *  - 3 ms attack / 6 ms release envelopes (no clicks at loop points)
 *  - soft tanh saturation, then a limiter ceiling at -1 dBFS
 *
 * Usage: node scripts/make-alarm-tone.mjs
 * Requires ffmpeg on PATH (or FFMPEG env var).
 */
import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const SAMPLE_RATE = 44100
const OUT = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'android',
  'app',
  'src',
  'main',
  'res',
  'raw',
  'studentos_alarm.mp3',
)

// ── Tone design ────────────────────────────────────────────────────────────
const BEEPS_PER_BURST = 3
const BEEP_S = 0.14
const BEEP_GAP_S = 0.08
const BURST_GAP_S = 0.44
const BURST_HZ = [1900, 2600, 1900, 2600] // alternates so it stays noticeable

const BEEP_PERIOD_S = BEEP_S + BEEP_GAP_S
const BURST_S = BEEPS_PER_BURST * BEEP_S + (BEEPS_PER_BURST - 1) * BEEP_GAP_S + BURST_GAP_S
const TOTAL_S = BURST_HZ.length * BURST_S

const ATTACK_S = 0.003
const RELEASE_S = 0.006
const AMPLITUDE = 0.85 // pre-saturation headroom; limiter handles the rest
// The synth peaks around -2 dBFS on its own; this drive makes the limiter do
// real work so the bursts sit at the ceiling instead of a couple of dB below it.
const DRIVE_DB = 5

function envelope(t, duration) {
  if (t < ATTACK_S) return t / ATTACK_S
  const fromEnd = duration - t
  if (fromEnd < RELEASE_S) return Math.max(0, fromEnd / RELEASE_S)
  return 1
}

/** One beep: fundamental + 2nd/3rd harmonic, soft-clipped for perceived loudness. */
function beep(t, duration, freq) {
  const env = envelope(t, duration)
  if (env <= 0) return 0
  const w = 2 * Math.PI * freq * t
  const raw = Math.sin(w) + 0.28 * Math.sin(2 * w) + 0.12 * Math.sin(3 * w)
  return AMPLITUDE * env * Math.tanh(raw * 1.15)
}

/** Absolute start time, in seconds, of a beep inside the loop. */
function beepStart(burstIndex, beepIndex) {
  return burstIndex * BURST_S + beepIndex * BEEP_PERIOD_S
}

function render() {
  const frames = Math.round(TOTAL_S * SAMPLE_RATE)
  const samples = new Float32Array(frames)

  BURST_HZ.forEach((freq, burstIndex) => {
    for (let beepIndex = 0; beepIndex < BEEPS_PER_BURST; beepIndex += 1) {
      const startFrame = Math.round(beepStart(burstIndex, beepIndex) * SAMPLE_RATE)
      const beepFrames = Math.round(BEEP_S * SAMPLE_RATE)
      for (let i = 0; i < beepFrames; i += 1) {
        const t = i / SAMPLE_RATE
        const out = startFrame + i
        if (out < frames) samples[out] = beep(t, BEEP_S, freq)
      }
    }
  })

  return samples
}

/** 16-bit PCM mono WAV. */
function toWav(samples) {
  const dataBytes = samples.length * 2
  const buffer = Buffer.alloc(44 + dataBytes)
  buffer.write('RIFF', 0)
  buffer.writeUInt32LE(36 + dataBytes, 4)
  buffer.write('WAVE', 8)
  buffer.write('fmt ', 12)
  buffer.writeUInt32LE(16, 16) // PCM chunk size
  buffer.writeUInt16LE(1, 20) // PCM
  buffer.writeUInt16LE(1, 22) // mono
  buffer.writeUInt32LE(SAMPLE_RATE, 24)
  buffer.writeUInt32LE(SAMPLE_RATE * 2, 28) // byte rate
  buffer.writeUInt16LE(2, 32) // block align
  buffer.writeUInt16LE(16, 34) // bits per sample
  buffer.write('data', 36)
  buffer.writeUInt32LE(dataBytes, 40)

  for (let i = 0; i < samples.length; i += 1) {
    const clamped = Math.max(-1, Math.min(1, samples[i]))
    buffer.writeInt16LE(Math.round(clamped * 32767), 44 + i * 2)
  }
  return buffer
}

const ffmpeg = process.env.FFMPEG || 'ffmpeg'
const workDir = mkdtempSync(join(tmpdir(), 'studentos-alarm-'))
const wavPath = join(workDir, 'studentos_alarm.wav')

try {
  writeFileSync(wavPath, toWav(render()))

  // Peak-limit to -1 dBFS (-1 dB ≈ linear 0.891) and encode a small mono MP3.
  execFileSync(
    ffmpeg,
    [
      '-y',
      '-hide_banner',
      '-loglevel', 'error',
      '-i', wavPath,
      '-af', `volume=${DRIVE_DB}dB,alimiter=limit=0.891:level=disabled`,
      '-ac', '1',
      '-ar', String(SAMPLE_RATE),
      '-codec:a', 'libmp3lame',
      '-b:a', '128k',
      OUT,
    ],
    { stdio: 'inherit' },
  )

  const sizeKb = Math.round(statSync(OUT).size / 1024)
  console.log(`wrote ${OUT} (${sizeKb} KB, ${TOTAL_S.toFixed(2)} s loop)`)
} finally {
  rmSync(workDir, { recursive: true, force: true })
}
