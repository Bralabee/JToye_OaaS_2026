"use client"

import { useCallback, useEffect, useRef, useState } from "react"
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

  const enableSound = useCallback(() => {
    let ctx = ctxRef.current
    if (!ctx || ctx.state === "closed") {
      const Ctor = audioContextConstructor()
      if (!Ctor) {
        setRunning(false)
        return
      }
      ctx = new Ctor()
      ctxRef.current = ctx
    }
    const created = ctx
    if (created.state !== "running") {
      created.resume().then(
        () => setRunning(created.state === "running"),
        () => setRunning(false)
      )
    }
    setRunning(created.state === "running")
  }, [])

  // The board owns the context: leaving the page releases it.
  useEffect(() => {
    return () => {
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

const CONTROL_LABEL: Record<SoundState, string> = {
  off: "Turn on sound",
  on: "Mute alerts",
  muted: "Unmute alerts",
}

/** The header sound control: it says what tapping it will do, in words. */
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
  return (
    <Button
      variant="outline"
      data-testid="kds-sound-control"
      onClick={onClick}
      className="kds-press"
    >
      {CONTROL_LABEL[soundState]}
    </Button>
  )
}
