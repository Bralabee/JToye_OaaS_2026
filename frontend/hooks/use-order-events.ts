"use client"

import { useEffect, useRef } from "react"
import { fetchEventSource } from "@microsoft/fetch-event-source"
import { getSession } from "next-auth/react"
import type { OrderStateChangeEvent } from "@/types/api"

/** Initial reconnect delay; doubles per consecutive failed attempt. */
export const SSE_INITIAL_RETRY_MS = 1_000
/** Reconnect delay ceiling. */
export const SSE_MAX_RETRY_MS = 30_000

/**
 * Capped exponential backoff with jitter for SSE reconnects (#92).
 *
 * Delay = min(30s, 1s * 2^attempt), then multiplied by a random factor in
 * [0.75, 1.25) so a fleet of dashboards dropped by the same proxy restart
 * doesn't reconnect in lockstep. `random` is injectable for deterministic
 * tests.
 */
export function getRetryDelayMs(
  attempt: number,
  random: () => number = Math.random
): number {
  const exponential = Math.min(
    SSE_MAX_RETRY_MS,
    SSE_INITIAL_RETRY_MS * 2 ** Math.min(attempt, 10)
  )
  const jitterFactor = 0.75 + random() * 0.5
  return Math.round(exponential * jitterFactor)
}

/**
 * Subscribes to the tenant's order-state-change SSE stream
 * (`GET /api/v1/orders/stream`) and invokes `onEvent` for every event.
 *
 * Owns the concerns both dashboard pages used to hand-roll (and get wrong,
 * per issue #92 — they gave up permanently on the first error):
 *
 * - Auth: EventSource cannot attach an Authorization header, so this uses
 *   fetchEventSource with the NextAuth access token — refreshed on EVERY
 *   (re)connect attempt, because the server recycles emitters every 5 minutes
 *   and tokens expire well within a dashboard's lifetime.
 * - Reconnect: the connect loop never gives up while mounted. Failures back
 *   off exponentially (capped, jittered); a successful open resets the
 *   backoff; a graceful server close (emitter recycle) reconnects promptly.
 * - Cleanup: unmount aborts the in-flight connection and stops the loop.
 *
 * The latest `onEvent` is kept in a ref, so callers may pass inline closures
 * without re-triggering the connection effect.
 */
export function useOrderEvents(
  onEvent: (event: OrderStateChangeEvent) => void
): void {
  const onEventRef = useRef(onEvent)
  useEffect(() => {
    onEventRef.current = onEvent
  }, [onEvent])

  useEffect(() => {
    const apiUrl = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"
    const abortCtrl = new AbortController()
    let attempt = 0

    // Abort-aware sleep: resolves early (instead of leaking a timer) when the
    // component unmounts mid-backoff.
    const sleep = (ms: number) =>
      new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, ms)
        abortCtrl.signal.addEventListener(
          "abort",
          () => {
            clearTimeout(timer)
            resolve()
          },
          { once: true }
        )
      })

    ;(async () => {
      while (!abortCtrl.signal.aborted) {
        let token: string | undefined
        try {
          const session = await getSession()
          token = session?.accessToken
        } catch {
          // getSession failed — treat like a connection failure: back off below.
        }
        if (abortCtrl.signal.aborted) return

        if (token) {
          try {
            await fetchEventSource(`${apiUrl}/api/v1/orders/stream`, {
              signal: abortCtrl.signal,
              headers: { Authorization: `Bearer ${token}` },
              openWhenHidden: true,
              onopen: async (res) => {
                if (res.ok) {
                  attempt = 0 // healthy connection resets the backoff
                  return
                }
                throw new Error(`SSE connect failed with HTTP ${res.status}`)
              },
              onmessage: (ev) => {
                // Heartbeat comments never reach onmessage; only real events do.
                if (ev.event !== "order-state-change") return
                try {
                  onEventRef.current(JSON.parse(ev.data) as OrderStateChangeEvent)
                } catch {
                  // Malformed event — ignore.
                }
              },
              // Throwing exits fetchEventSource's internal retry loop and hands
              // control back to the outer loop, which owns the backoff AND
              // refreshes the (possibly expired) token before the next attempt.
              onerror: (err) => {
                throw err
              },
            })
            // Resolved without throwing: the server closed the stream gracefully
            // (the emitter recycles every 5 minutes). Fall through and reconnect.
          } catch {
            // Dropped/refused — fall through to the backoff.
          }
        }

        if (abortCtrl.signal.aborted) return
        await sleep(getRetryDelayMs(attempt++))
      }
    })()

    return () => abortCtrl.abort()
    // The stream endpoint is constant; onEvent updates flow through the ref.
     
  }, [])
}
