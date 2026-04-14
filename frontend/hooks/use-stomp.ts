"use client"

import { useEffect, useRef, useState, useCallback } from "react"
import { Client, IMessage } from "@stomp/stompjs"
import { getSession } from "next-auth/react"
import type { OrderStateChangeEvent } from "@/types/api"

function getWsBrokerUrl(token: string): string {
  const apiUrl = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"
  // Strip protocol and derive ws:// or wss://
  const url = new URL(apiUrl)
  const wsProtocol = url.protocol === "https:" ? "wss:" : "ws:"
  return `${wsProtocol}//${url.host}/ws?token=${token}`
}

export function useStomp(
  topic: string | null,
  onMessage: (event: OrderStateChangeEvent) => void,
  onReconnect?: () => void
): { connected: boolean; reconnecting: boolean } {
  const [connected, setConnected] = useState(false)
  const [reconnecting, setReconnecting] = useState(false)
  const clientRef = useRef<Client | null>(null)
  const onMessageRef = useRef(onMessage)
  const onReconnectRef = useRef(onReconnect)

  // Keep refs current without re-triggering effect
  useEffect(() => {
    onMessageRef.current = onMessage
  }, [onMessage])

  useEffect(() => {
    onReconnectRef.current = onReconnect
  }, [onReconnect])

  const connect = useCallback(() => {
    if (!topic) return

    // Clean up any existing client
    if (clientRef.current) {
      clientRef.current.deactivate()
      clientRef.current = null
    }

    let wasConnectedBefore = false

    const client = new Client({
      reconnectDelay: 5000,
      beforeConnect: async () => {
        // Fetch fresh JWT on every connect/reconnect (handles token refresh per T-03).
        // A failing getSession() must NOT crash the hook — fall back to an empty
        // token and let the broker reject the connection cleanly so the retry
        // loop keeps running.
        let token = ""
        try {
          const session = await getSession()
          token = session?.accessToken || ""
        } catch (err) {
          console.warn("useStomp: getSession() failed, connecting without token", err)
        }
        client.brokerURL = getWsBrokerUrl(token)
      },
      onConnect: () => {
        setConnected(true)
        setReconnecting(false)

        // If this is a reconnect, trigger full data resync
        if (wasConnectedBefore && onReconnectRef.current) {
          onReconnectRef.current()
        }
        wasConnectedBefore = true

        client.subscribe(topic, (message: IMessage) => {
          try {
            const event: OrderStateChangeEvent = JSON.parse(message.body)
            onMessageRef.current(event)
          } catch {
            console.error("Failed to parse STOMP message:", message.body)
          }
        })
      },
      onStompError: (frame) => {
        console.error("STOMP error:", frame.headers["message"])
        setConnected(false)
        setReconnecting(true)
      },
      onWebSocketClose: () => {
        setConnected(false)
        if (wasConnectedBefore) {
          setReconnecting(true)
        }
      },
    })

    // Set initial broker URL (will be overridden by beforeConnect)
    client.brokerURL = getWsBrokerUrl("")

    clientRef.current = client
    client.activate()
  }, [topic])

  useEffect(() => {
    connect()

    return () => {
      if (clientRef.current) {
        clientRef.current.deactivate()
        clientRef.current = null
      }
      setConnected(false)
      setReconnecting(false)
    }
  }, [connect])

  return { connected, reconnecting }
}
