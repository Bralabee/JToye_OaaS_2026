/**
 * A recording stand-in for the Web Audio `AudioContext` (37-17, D-12).
 *
 * jsdom has no Web Audio at all, so the kitchen alert can only be proven by counting what it
 * ASKS the audio graph to do. Every oscillator start is recorded with its frequency, waveform and
 * the gain it was routed through, so a test can tell the two-tone chime (880 Hz then 660 Hz, sine,
 * gain 0.6) from any other sound, and count how many times it rang.
 *
 * `state` starts as `FakeAudioContext.initialState` ("running" unless a test says otherwise), which
 * is what a context created inside a user gesture reports in a real browser. `suspendFromOutside`
 * plays the part of the browser or OS taking the audio away (a phone call, an iOS interruption, a
 * tab policy): the state flips and `statechange` fires, exactly as the real object does.
 *
 * Install with `installFakeAudioContext()` in a `beforeEach`; it also resets the recordings.
 */

type Listener = () => void

export interface OscillatorStart {
  frequency: number
  type: string
  gain: number | null
  at: number
}

export const audioRecord: {
  instances: FakeAudioContext[]
  starts: OscillatorStart[]
} = { instances: [], starts: [] }

class FakeGain {
  value = 1
  setValueAtTime(v: number) {
    this.value = v
    return this
  }
  linearRampToValueAtTime() {
    return this
  }
  exponentialRampToValueAtTime() {
    return this
  }
  cancelScheduledValues() {
    return this
  }
}

class FakeGainNode {
  gain = new FakeGain()
  peak: number | null = null
  connect() {}
  disconnect() {}
}

class FakeOscillatorNode {
  type = "sine"
  private target: FakeGainNode | null = null
  frequency = {
    value: 440,
    setValueAtTime: (v: number) => {
      this.frequency.value = v
    },
  }
  onended: (() => void) | null = null
  connect(node: unknown) {
    if (node instanceof FakeGainNode) this.target = node
  }
  disconnect() {}
  start(at = 0) {
    audioRecord.starts.push({
      frequency: this.frequency.value,
      type: this.type,
      gain: this.target ? this.target.peak ?? this.target.gain.value : null,
      at,
    })
  }
  stop() {}
}

export class FakeAudioContext {
  static initialState: "running" | "suspended" = "running"
  /** When true, `resume()` leaves the context suspended (the browser refused). */
  static resumeBlocked = false
  state: "running" | "suspended" | "closed" | "interrupted"
  currentTime = 0
  destination = {}
  private listeners = new Set<Listener>()
  onstatechange: Listener | null = null

  constructor() {
    this.state = FakeAudioContext.initialState
    audioRecord.instances.push(this)
  }

  createOscillator() {
    return new FakeOscillatorNode()
  }

  createGain() {
    const node = new FakeGainNode()
    // The peak is the gain the chime is played at: remember the largest value it is set to.
    const original = node.gain.setValueAtTime.bind(node.gain)
    node.gain.setValueAtTime = (v: number) => {
      node.peak = Math.max(node.peak ?? 0, v)
      return original(v)
    }
    const ramp = node.gain.linearRampToValueAtTime.bind(node.gain)
    node.gain.linearRampToValueAtTime = ((v: number) => {
      node.peak = Math.max(node.peak ?? 0, v)
      return ramp()
    }) as typeof node.gain.linearRampToValueAtTime
    return node
  }

  resume() {
    if (FakeAudioContext.resumeBlocked) return Promise.resolve()
    this.setState("running")
    return Promise.resolve()
  }

  suspend() {
    this.setState("suspended")
    return Promise.resolve()
  }

  close() {
    this.setState("closed")
    return Promise.resolve()
  }

  /** The browser takes the audio away (not something the page asked for). */
  suspendFromOutside() {
    this.setState("suspended")
  }

  addEventListener(type: string, fn: Listener) {
    if (type === "statechange") this.listeners.add(fn)
  }

  removeEventListener(type: string, fn: Listener) {
    if (type === "statechange") this.listeners.delete(fn)
  }

  private setState(next: FakeAudioContext["state"]) {
    if (this.state === next) return
    this.state = next
    this.listeners.forEach((fn) => fn())
    this.onstatechange?.()
  }
}

/** The context the board built (the board only ever builds one). */
export function onlyContext(): FakeAudioContext {
  if (audioRecord.instances.length !== 1) {
    throw new Error(`expected exactly one AudioContext, found ${audioRecord.instances.length}`)
  }
  return audioRecord.instances[0]
}

/** Install `navigator.getAutoplayPolicy` returning `policy` for every context. */
export function installAutoplayPolicy(policy: "allowed" | "allowed-muted" | "disallowed") {
  ;(navigator as unknown as { getAutoplayPolicy: unknown }).getAutoplayPolicy = jest.fn(() => policy)
}

/** Two oscillator starts make one chime: the number of 880 Hz starts is the number of chimes. */
export function chimeCount(): number {
  return audioRecord.starts.filter((s) => s.frequency === 880).length
}

export function installFakeAudioContext(initialState: "running" | "suspended" = "running") {
  audioRecord.instances.length = 0
  audioRecord.starts.length = 0
  FakeAudioContext.initialState = initialState
  FakeAudioContext.resumeBlocked = false
  delete (navigator as unknown as { getAutoplayPolicy?: unknown }).getAutoplayPolicy
  ;(window as unknown as { AudioContext: unknown }).AudioContext = FakeAudioContext
  ;(globalThis as unknown as { AudioContext: unknown }).AudioContext = FakeAudioContext
}
