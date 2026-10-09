/**
 * 37-17 (D-12, UXT-020): the new-order chime repeats until the order is accepted.
 *
 * Before this plan a new order played ONE 800 Hz beep through a throwaway `new AudioContext()`
 * created inside a socket callback. A context created outside a user gesture is suspended by the
 * browser, the error was swallowed, and the board said nothing about it: a new order could arrive
 * in silence and stay silent. These tests count what the alert asks the audio graph to do (jsdom
 * has no Web Audio; see test-utils/fake-audio-context.ts) under fake timers:
 *
 *   - no tap, no AudioContext at all (nothing is started outside a gesture);
 *   - the tap creates exactly one context, synchronously, inside the handler;
 *   - one PENDING order rings at least three times in 15 s, as 880 Hz then 660 Hz sine at 0.6;
 *   - after Accept (pending 0), zero starts in the next 15 s;
 *   - muted, or the board stopped: zero starts.
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react"
import {
  KdsSoundBar,
  KdsSoundControl,
  NEW_ORDER_ALERT_INTERVAL_MS,
  useKitchenAlert,
  type KitchenAlertInputs,
  type SoundState,
} from "../kds-sound"
import {
  FakeAudioContext,
  audioRecord,
  chimeCount,
  installAutoplayPolicy,
  installFakeAudioContext,
  onlyContext,
} from "@/test-utils/fake-audio-context"

function Harness(props: KitchenAlertInputs) {
  const { soundState, enableSound } = useKitchenAlert(props)
  return (
    <div>
      <span data-testid="sound-state">{soundState}</span>
      <button type="button" onClick={enableSound}>
        Turn on sound
      </button>
    </div>
  )
}

const advance = (ms: number) =>
  act(() => {
    jest.advanceTimersByTime(ms)
  })

beforeEach(() => {
  jest.useFakeTimers()
  installFakeAudioContext("running")
})

afterEach(() => {
  jest.useRealTimers()
})

describe("useKitchenAlert (D-12): the chime repeats until the order is accepted", () => {
  it("names the repeat interval as a constant: 5 s", () => {
    expect(NEW_ORDER_ALERT_INTERVAL_MS).toBe(5000)
  })

  it("constructs NO AudioContext until the operator taps, so nothing starts outside a gesture", () => {
    render(<Harness pendingCount={1} muted={false} stopped={false} />)
    advance(15_000)
    expect(audioRecord.instances).toHaveLength(0)
    expect(audioRecord.starts).toHaveLength(0)
  })

  it("creates exactly one context, synchronously, inside the 'Turn on sound' tap", () => {
    render(<Harness pendingCount={0} muted={false} stopped={false} />)
    expect(audioRecord.instances).toHaveLength(0)
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    // No await, no timer: the context exists before the handler returns.
    expect(audioRecord.instances).toHaveLength(1)
    // A second tap re-uses it: one shared context for the board.
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    expect(audioRecord.instances).toHaveLength(1)
  })

  it("rings at least three times in 15 s while one PENDING order waits (880 Hz then 660 Hz, sine, 0.6)", () => {
    render(<Harness pendingCount={1} muted={false} stopped={false} />)
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    advance(15_000)

    expect(chimeCount()).toBeGreaterThanOrEqual(3)
    // Every chime is the two-tone pattern, in order, and nothing else is played.
    const pairs = audioRecord.starts.map((s) => s.frequency)
    expect(pairs.length).toBe(chimeCount() * 2)
    for (let i = 0; i < pairs.length; i += 2) {
      expect([pairs[i], pairs[i + 1]]).toEqual([880, 660])
    }
    for (const s of audioRecord.starts) {
      expect(s.type).toBe("sine")
      expect(s.gain).toBeCloseTo(0.6)
    }
    // The second tone starts 150 ms after the first.
    expect(audioRecord.starts[1].at - audioRecord.starts[0].at).toBeCloseTo(0.15)
  })

  it("stops ringing once the last PENDING order is accepted: zero starts in the next 15 s", () => {
    const { rerender } = render(<Harness pendingCount={1} muted={false} stopped={false} />)
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    advance(6_000)
    expect(chimeCount()).toBeGreaterThanOrEqual(1)

    rerender(<Harness pendingCount={0} muted={false} stopped={false} />)
    audioRecord.starts.length = 0
    advance(15_000)
    expect(audioRecord.starts).toHaveLength(0)
  })

  it("rings again when a new order arrives after the lane was empty", () => {
    const { rerender } = render(<Harness pendingCount={0} muted={false} stopped={false} />)
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    advance(15_000)
    expect(audioRecord.starts).toHaveLength(0)

    rerender(<Harness pendingCount={1} muted={false} stopped={false} />)
    advance(15_000)
    expect(chimeCount()).toBeGreaterThanOrEqual(3)
  })

  it("is silent while muted, with an order waiting", () => {
    const { rerender } = render(<Harness pendingCount={1} muted={false} stopped={false} />)
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    advance(1_000)
    expect(chimeCount()).toBeGreaterThanOrEqual(1)

    rerender(<Harness pendingCount={1} muted stopped={false} />)
    audioRecord.starts.length = 0
    advance(15_000)
    expect(audioRecord.starts).toHaveLength(0)
  })

  it("is silent once the board has stopped, with an order waiting", () => {
    const { rerender } = render(<Harness pendingCount={1} muted={false} stopped={false} />)
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    advance(1_000)
    expect(chimeCount()).toBeGreaterThanOrEqual(1)

    rerender(<Harness pendingCount={1} muted={false} stopped />)
    audioRecord.starts.length = 0
    advance(15_000)
    expect(audioRecord.starts).toHaveLength(0)
  })
})

/**
 * Task 2 (UI-SPEC K1 "Sound state: shown, never assumed"). The board must not claim sound it
 * cannot make: the state is read from the context itself, never assumed from the tap.
 */
describe("useKitchenAlert: the sound state is read, never assumed", () => {
  it("falls back to 'off' when the context is suspended later by the browser, and stops ringing", () => {
    render(<Harness pendingCount={1} muted={false} stopped={false} />)
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    expect(screen.getByTestId("sound-state")).toHaveTextContent("on")

    act(() => {
      onlyContext().suspendFromOutside()
    })
    expect(screen.getByTestId("sound-state")).toHaveTextContent("off")
    audioRecord.starts.length = 0
    advance(15_000)
    expect(audioRecord.starts).toHaveLength(0)
  })

  it("comes back 'on' when the next tap resumes the SAME context", async () => {
    render(<Harness pendingCount={0} muted={false} stopped={false} />)
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    act(() => {
      onlyContext().suspendFromOutside()
    })
    expect(screen.getByTestId("sound-state")).toHaveTextContent("off")
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    })
    expect(audioRecord.instances).toHaveLength(1)
    expect(screen.getByTestId("sound-state")).toHaveTextContent("on")
  })

  describe("navigator.getAutoplayPolicy is feature-detected", () => {
    it("present and 'disallowed': off, even though the context reports running", () => {
      installAutoplayPolicy("disallowed")
      render(<Harness pendingCount={1} muted={false} stopped={false} />)
      fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
      expect(onlyContext().state).toBe("running")
      expect(screen.getByTestId("sound-state")).toHaveTextContent("off")
      advance(15_000)
      expect(audioRecord.starts).toHaveLength(0)
    })

    it("present and 'allowed' with a running context: on", () => {
      installAutoplayPolicy("allowed")
      render(<Harness pendingCount={0} muted={false} stopped={false} />)
      fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
      expect(screen.getByTestId("sound-state")).toHaveTextContent("on")
      expect(navigator.getAutoplayPolicy).toHaveBeenCalledWith(onlyContext())
    })

    it("absent: AudioContext.state decides, running is on", () => {
      expect("getAutoplayPolicy" in navigator).toBe(false)
      render(<Harness pendingCount={0} muted={false} stopped={false} />)
      fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
      expect(screen.getByTestId("sound-state")).toHaveTextContent("on")
    })

    it("absent: AudioContext.state decides, a context the browser keeps suspended is off", async () => {
      installFakeAudioContext("suspended")
      FakeAudioContext.resumeBlocked = true
      render(<Harness pendingCount={1} muted={false} stopped={false} />)
      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
      })
      expect(onlyContext().state).toBe("suspended")
      expect(screen.getByTestId("sound-state")).toHaveTextContent("off")
      advance(15_000)
      expect(audioRecord.starts).toHaveLength(0)
    })
  })
})

describe("KdsSoundControl and KdsSoundBar: what the board can do, in words", () => {
  const noop = () => {}
  const renderBoth = (soundState: SoundState) =>
    render(
      <div>
        <KdsSoundControl soundState={soundState} onTurnOn={noop} onMute={noop} onUnmute={noop} />
        <KdsSoundBar soundState={soundState} onTurnOn={noop} onUnmute={noop} />
      </div>
    )

  it("no context yet: the control reads 'Turn on sound' (VolumeX) and the amber bar says sound is off", () => {
    renderBoth("off")
    const control = screen.getByTestId("kds-sound-control")
    expect(control).toHaveTextContent("Turn on sound")
    expect(control.querySelector("svg.lucide-volume-x")).not.toBeNull()
    expect(control.className).toMatch(/\bh-11\b/)
    expect(control.className).toMatch(/\bkds-press\b/)

    const bar = screen.getByTestId("kds-sound-bar")
    expect(bar).toHaveTextContent("Sound is off. New orders will flash but not ring.")
    expect(bar.className).toMatch(/\bbg-amber-50\b/)
    const action = within(bar).getByRole("button", { name: "Turn on sound" })
    expect(action.className).toMatch(/\bh-11\b/)
  })

  it("enabled: the control reads 'Mute alerts' (Volume2) and there is no bar", () => {
    renderBoth("on")
    const control = screen.getByTestId("kds-sound-control")
    expect(control).toHaveTextContent("Mute alerts")
    expect(control.querySelector("svg.lucide-volume-2")).not.toBeNull()
    expect(screen.queryByTestId("kds-sound-bar")).toBeNull()
  })

  it("muted: the control reads 'Unmute alerts' (BellOff, amber-800) and the bar says alerts are muted", () => {
    renderBoth("muted")
    const control = screen.getByTestId("kds-sound-control")
    expect(control).toHaveTextContent("Unmute alerts")
    expect(control.querySelector("svg.lucide-bell-off")).not.toBeNull()
    expect(control.className).toMatch(/\btext-amber-800\b/)

    const bar = screen.getByTestId("kds-sound-bar")
    expect(bar).toHaveTextContent("Alerts are muted. New orders will flash but not ring.")
    expect(within(bar).getByRole("button", { name: "Unmute alerts" })).toBeInTheDocument()
  })

  it.each(["off", "muted"] as const)("the %s bar cannot be dismissed: its one button is the fix", (state) => {
    renderBoth(state)
    const bar = screen.getByTestId("kds-sound-bar")
    const buttons = within(bar).getAllByRole("button")
    expect(buttons).toHaveLength(1)
    expect(buttons[0]).toHaveTextContent(state === "off" ? "Turn on sound" : "Unmute alerts")
    expect(within(bar).queryByRole("button", { name: /dismiss|close|hide|×/i })).toBeNull()
  })

  it("the bar's button calls the same action as the control", () => {
    const onTurnOn = jest.fn()
    const onUnmute = jest.fn()
    const { rerender } = render(<KdsSoundBar soundState="off" onTurnOn={onTurnOn} onUnmute={onUnmute} />)
    fireEvent.click(screen.getByRole("button", { name: "Turn on sound" }))
    expect(onTurnOn).toHaveBeenCalledTimes(1)
    rerender(<KdsSoundBar soundState="muted" onTurnOn={onTurnOn} onUnmute={onUnmute} />)
    fireEvent.click(screen.getByRole("button", { name: "Unmute alerts" }))
    expect(onUnmute).toHaveBeenCalledTimes(1)
  })
})
