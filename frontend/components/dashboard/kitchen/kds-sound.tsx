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
}

export interface KitchenAlert {
  soundState: SoundState
  /** Call ONLY from a tap handler: it is the user gesture that unlocks audio. */
  enableSound: () => void
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

export function useKitchenAlert({
  pendingCount,
  muted,
  stopped,
}: KitchenAlertInputs): KitchenAlert {
  const ctxRef = useRef<AudioContext | null>(null)
  // Whether the board's one context can make sound right now. State, not a read of the ref, so
  // the board re-renders when it changes.
  const [running, setRunning] = useState(false)
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

  return { soundState, enableSound }
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

// 37-18 RED interface stub: the board-stopped alarm. Replaced in the GREEN commit.
export const BOARD_STOPPED_ALARM_INTERVAL_MS = 3000
export const BOARD_STOPPED_ALARM = {
  toneHz: 440,
  toneSeconds: 0.12,
  gapSeconds: 0.08,
  count: 3,
  type: "square" as OscillatorType,
  gain: 0.5,
} as const
export function playAlarmPattern(_ctx: AudioContext): void {}
