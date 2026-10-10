"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { BellOff, Volume2, VolumeX } from "lucide-react"
import { Button } from "@/components/ui/button"

/**
 * 37-17 (D-12, UXT-020): the kitchen's new-order alert.
 *
 * WHAT IT REPLACES. The board used to play one 800 Hz beep through a `new AudioContext()` built
 * inside the socket callback, with every error swallowed. Browsers only let audio start from a
 * user gesture (MDN, Autoplay guide: Web Audio), so a context created there is suspended and the
 * beep is lost. Nothing on the board said so. A new order could arrive in silence, ring at most
 * once, and never ring again.
 *
 * WHAT IT DOES NOW.
 *   - ONE `AudioContext` for the board, created only by `enableSound()`, which is called from a
 *     tap handler ("Turn on sound"). Nothing here ever constructs one outside that call.
 *   - While at least one PENDING order waits on this board, sound is on, the operator has not
 *     muted and the board has not stopped, a two-tone chime plays at once and then every
 *     {@link NEW_ORDER_ALERT_INTERVAL_MS}. It stops when the last order is accepted or rejected,
 *     on mute, on a shop switch (the page passes this shop's count) and when the board stops.
 *   - The board SAYS whether it can ring (`soundState`), read from the context itself on every
 *     `statechange`, never assumed from the tap: a context the browser suspends later (a call,
 *     an OS interruption, a tab policy) turns the state back to "off" and the bar comes back.
 */

/** How often the new-order chime repeats while an order waits to be accepted. */
export const NEW_ORDER_ALERT_INTERVAL_MS = 5000

/** The new-order chime: 880 Hz then 660 Hz, 150 ms each, sine, at gain 0.6 (UI-SPEC K1). */
export const NEW_ORDER_CHIME = {
  tonesHz: [880, 660],
  toneSeconds: 0.15,
  type: "sine" as OscillatorType,
  gain: 0.6,
} as const

/** Tab title for the kitchen board: the New-lane count leads, so a background tab still calls. */
export function kitchenTabTitle(newCount: number): string {
  return newCount > 0
    ? `(${newCount}) New orders · Kitchen Display — J'Toye`
    : "Kitchen Display — J'Toye"
}

export type SoundState = "off" | "on" | "muted"

export interface KitchenAlertInputs {
  /** PENDING orders on this board's New lane, for the shop the board is showing. */
  pendingCount: number
  /** The operator's persisted `kds-muted` preference. */
  muted: boolean
  /** The board has stopped (37-18, K2): the chime gives way to the alarm. */
  stopped: boolean
  /**
   * Ring the board-stopped alarm (37-18, K2): the board has stopped and nobody has pressed
   * "Silence alarm". It plays through the same context, whether or not new-order alerts are muted:
   * mute silences the chime, and the overlay's own button silences the alarm.
   */
  alarm?: boolean
}

export interface KitchenAlert {
  soundState: SoundState
  /** Call ONLY from a tap handler: it is the user gesture that unlocks audio. */
  enableSound: () => void
  /**
   * A tap has created the board's context (37-18). False means sound was never turned on, which
   * the board-stopped overlay says in words because it then cannot ring.
   */
  soundEnabled: boolean
}

type AudioContextCtor = new () => AudioContext

function audioContextConstructor(): AudioContextCtor | null {
  if (typeof window === "undefined") return null
  const w = window as unknown as {
    AudioContext?: AudioContextCtor
    webkitAudioContext?: AudioContextCtor
  }
  return w.AudioContext ?? w.webkitAudioContext ?? null
}

/**
 * Can this context make sound right now?
 *
 * `navigator.getAutoplayPolicy(context)` is asked first where the browser has it (RESEARCH A7: its
 * availability is not assumed, so it is feature-detected); anything but "allowed" is a no. Where it
 * is absent, or throws, `AudioContext.state` decides. Either way a context that is not "running"
 * plays nothing, so it never counts as sound.
 */
export function contextCanSound(ctx: AudioContext | null): boolean {
  if (!ctx) return false
  const nav = (typeof navigator === "undefined" ? undefined : navigator) as
    | { getAutoplayPolicy?: (target: AudioContext) => string }
    | undefined
  if (nav && typeof nav.getAutoplayPolicy === "function") {
    try {
      if (nav.getAutoplayPolicy(ctx) !== "allowed") return false
    } catch {
      // A browser that has the method but rejects the argument: fall back to the state.
    }
  }
  return ctx.state === "running"
}

/** Schedule one chime on a running context. Exported for the stopped-board alarm to sit beside. */
export function playNewOrderChime(ctx: AudioContext): void {
  if (ctx.state !== "running") return
  const { tonesHz, toneSeconds, type, gain: peak } = NEW_ORDER_CHIME
  const t0 = ctx.currentTime
  const end = t0 + tonesHz.length * toneSeconds
  const gain = ctx.createGain()
  // A 10 ms ramp at each edge: a square edge on a sine is an audible click.
  gain.gain.setValueAtTime(0.0001, t0)
  gain.gain.linearRampToValueAtTime(peak, t0 + 0.01)
  gain.gain.setValueAtTime(peak, end - 0.01)
  gain.gain.linearRampToValueAtTime(0.0001, end)
  gain.connect(ctx.destination)
  tonesHz.forEach((hz, i) => {
    const osc = ctx.createOscillator()
    osc.type = type
    osc.frequency.setValueAtTime(hz, t0 + i * toneSeconds)
    osc.connect(gain)
    osc.start(t0 + i * toneSeconds)
    osc.stop(t0 + (i + 1) * toneSeconds)
  })
}

/** How often the board-stopped alarm repeats (UI-SPEC K2: every 3 s). */
export const BOARD_STOPPED_ALARM_INTERVAL_MS = 3000

/**
 * How long an unsilenced board-stopped alarm rings before it stops by itself: 2 minutes from the
 * first burst of a stopped-board episode. Owner-accepted 2026-10-10 (quick 261010-ppb): alarm
 * fatigue. An abandoned tablet must not ring all night, and an alarm nobody can stop teaches people
 * to turn the sound off. The red overlay stays until the page is left; only the sound ends.
 */
export const BOARD_STOPPED_ALARM_MAX_MS = 120_000

/**
 * The board-stopped alarm (37-18, UI-SPEC K2): three 440 Hz `square` tones, 120 ms each, 80 ms
 * apart. A different pitch AND a different waveform from the new-order chime (880/660 Hz sine), so
 * the two are told apart by ear: one means "an order waits", the other "the board is not working".
 */
export const BOARD_STOPPED_ALARM = {
  toneHz: 440,
  toneSeconds: 0.12,
  gapSeconds: 0.08,
  count: 3,
  type: "square" as OscillatorType,
  // A square wave is perceptibly louder than a sine at the same gain; 0.5 keeps it at or below the
  // chime's level while staying distinct. A named value, never a literal at the call site.
  gain: 0.5,
} as const

/** Schedule one alarm burst on a running context: the same shared context the chime uses. */
export function playAlarmPattern(ctx: AudioContext): void {
  if (ctx.state !== "running") return
  const { toneHz, toneSeconds, gapSeconds, count, type, gain: peak } = BOARD_STOPPED_ALARM
  const t0 = ctx.currentTime
  for (let i = 0; i < count; i++) {
    const start = t0 + i * (toneSeconds + gapSeconds)
    const end = start + toneSeconds
    // Each tone has its own envelope: a 5 ms ramp at each edge, so the gap is silent and clean.
    const gain = ctx.createGain()
    gain.gain.setValueAtTime(0.0001, start)
    gain.gain.linearRampToValueAtTime(peak, start + 0.005)
    gain.gain.setValueAtTime(peak, end - 0.005)
    gain.gain.linearRampToValueAtTime(0.0001, end)
    gain.connect(ctx.destination)
    const osc = ctx.createOscillator()
    osc.type = type
    osc.frequency.setValueAtTime(toneHz, start)
    osc.connect(gain)
    osc.start(start)
    osc.stop(end)
  }
}

export function useKitchenAlert({
  pendingCount,
  muted,
  stopped,
  alarm = false,
}: KitchenAlertInputs): KitchenAlert {
  const ctxRef = useRef<AudioContext | null>(null)
  // Whether the board's one context can make sound right now. State, not a read of the ref, so
  // the board re-renders when it changes.
  const [running, setRunning] = useState(false)
  // 37-18: a tap has created the context. Kept as state so the board-stopped overlay can say, in
  // words, that a board whose sound was never turned on cannot ring.
  const [soundEnabled, setSoundEnabled] = useState(false)
  const detachRef = useRef<(() => void) | null>(null)

  const enableSound = useCallback(() => {
    let ctx = ctxRef.current
    if (!ctx || ctx.state === "closed") {
      const Ctor = audioContextConstructor()
      if (!Ctor) {
        // No Web Audio at all: the board says so (the bar stays) instead of pretending.
        setRunning(false)
        return
      }
      ctx = new Ctor()
      ctxRef.current = ctx
      setSoundEnabled(true)
      const created = ctx
      const onStateChange = () => setRunning(contextCanSound(created))
      created.addEventListener("statechange", onStateChange)
      detachRef.current?.()
      detachRef.current = () => created.removeEventListener("statechange", onStateChange)
    }
    const current = ctx
    if (current.state !== "running") {
      // Still inside the tap: this is the gesture that lets a suspended context resume.
      current.resume().then(
        () => setRunning(contextCanSound(current)),
        () => setRunning(false)
      )
    }
    setRunning(contextCanSound(current))
  }, [])

  // The board owns the context: leaving the page releases it.
  useEffect(() => {
    return () => {
      detachRef.current?.()
      detachRef.current = null
      const ctx = ctxRef.current
      ctxRef.current = null
      if (ctx && ctx.state !== "closed") void ctx.close().catch(() => {})
    }
  }, [])

  const soundState: SoundState = muted ? "muted" : running ? "on" : "off"
  const ringing = pendingCount > 0 && !stopped && soundState === "on"

  useEffect(() => {
    const ctx = ctxRef.current
    if (!ringing || !ctx) return
    playNewOrderChime(ctx)
    const id = window.setInterval(() => playNewOrderChime(ctx), NEW_ORDER_ALERT_INTERVAL_MS)
    return () => window.clearInterval(id)
  }, [ringing])

  // 37-18 (K2): the board-stopped alarm, through the SAME context. It needs a context that can
  // sound (`running`), never the operator's mute: a stopped board is louder than a waiting order,
  // and the overlay's "Silence alarm" is what turns it off. No context means no alarm; the overlay
  // says so instead of building one outside a gesture, which the browser would suspend.
  const alarming = alarm && running

  // 261010-ppb (owner-accepted 2026-10-10, alarm fatigue): the alarm stops by itself
  // BOARD_STOPPED_ALARM_MAX_MS after its first burst. The cap is measured from the start of the
  // stopped-board episode, not from the latest render, so a context that is suspended and resumed
  // mid-alarm does not restart the two minutes. The episode ends only when `alarm` goes false.
  const alarmEpisodeStartRef = useRef<number | null>(null)
  useEffect(() => {
    if (!alarm) alarmEpisodeStartRef.current = null
  }, [alarm])

  useEffect(() => {
    const ctx = ctxRef.current
    if (!alarming || !ctx) return
    const now = Date.now()
    if (alarmEpisodeStartRef.current === null) alarmEpisodeStartRef.current = now
    const remaining = BOARD_STOPPED_ALARM_MAX_MS - (now - alarmEpisodeStartRef.current)
    if (remaining <= 0) return
    playAlarmPattern(ctx)
    const id = window.setInterval(() => playAlarmPattern(ctx), BOARD_STOPPED_ALARM_INTERVAL_MS)
    const cap = window.setTimeout(() => window.clearInterval(id), remaining)
    return () => {
      window.clearInterval(id)
      window.clearTimeout(cap)
    }
  }, [alarming])

  return { soundState, enableSound, soundEnabled }
}

const CONTROL: Record<
  SoundState,
  { label: string; Icon: typeof Volume2; text: string }
> = {
  off: { label: "Turn on sound", Icon: VolumeX, text: "" },
  on: { label: "Mute alerts", Icon: Volume2, text: "" },
  muted: { label: "Unmute alerts", Icon: BellOff, text: "text-amber-800" },
}

const BAR: Record<Exclude<SoundState, "on">, string> = {
  off: "Sound is off. New orders will flash but not ring.",
  muted: "Alerts are muted. New orders will flash but not ring.",
}

/**
 * The sound bar under the header (UI-SPEC K1, "Sound state: shown, never assumed").
 *
 * Shown whenever the board cannot ring: no context yet, a context the browser holds suspended, or
 * the operator's mute. It is NOT dismissible. A silent board in a noisy kitchen is the failure this
 * phase fixes, so the only button it has is the fix itself. Amber, the feed banner's tone for "the
 * board works, but know this"; the visual alert (the New card's ring) keeps running underneath.
 */
export function KdsSoundBar({
  soundState,
  onTurnOn,
  onUnmute,
}: {
  soundState: SoundState
  onTurnOn: () => void
  onUnmute: () => void
}) {
  if (soundState === "on") return null
  const action = CONTROL[soundState]
  const Icon = action.Icon
  return (
    <div
      role="status"
      data-testid="kds-sound-bar"
      className="flex flex-col gap-3 rounded-lg border-2 border-amber-500 bg-amber-50 px-4 py-3 text-amber-900 sm:flex-row sm:items-center sm:justify-between"
    >
      <p className="flex items-start gap-3 text-base font-semibold">
        <Icon aria-hidden className="mt-0.5 h-5 w-5 flex-shrink-0" />
        <span>{BAR[soundState]}</span>
      </p>
      <Button
        variant="outline"
        onClick={soundState === "off" ? onTurnOn : onUnmute}
        className="kds-press h-11 flex-shrink-0 border-current bg-white/70 px-5 text-base font-semibold"
      >
        {action.label}
      </Button>
    </div>
  )
}

/** The header sound control: it says what tapping it will do, in words, legible from a wall mount. */
export function KdsSoundControl({
  soundState,
  onTurnOn,
  onMute,
  onUnmute,
}: {
  soundState: SoundState
  onTurnOn: () => void
  onMute: () => void
  onUnmute: () => void
}) {
  const onClick =
    soundState === "off" ? onTurnOn : soundState === "on" ? onMute : onUnmute
  const { label, Icon, text } = CONTROL[soundState]
  return (
    <Button
      variant="outline"
      data-testid="kds-sound-control"
      onClick={onClick}
      className={`kds-press h-11 ${text}`.trim()}
    >
      <Icon aria-hidden className="mr-2 h-5 w-5" />
      {label}
    </Button>
  )
}
