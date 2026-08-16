import '@testing-library/jest-dom'

// Mock NextAuth
jest.mock('next-auth/react', () => ({
  useSession: jest.fn(() => ({
    data: {
      user: { name: 'Test User', email: 'test@example.com' },
      accessToken: 'mock-access-token',
      expires: '2099-12-31',
    },
    status: 'authenticated',
  })),
  signIn: jest.fn(),
  signOut: jest.fn(),
  getSession: jest.fn(() => Promise.resolve({
    user: { name: 'Test User', email: 'test@example.com' },
    accessToken: 'mock-access-token',
    expires: '2099-12-31',
  })),
}))

// Mock next/navigation
jest.mock('next/navigation', () => ({
  useRouter: jest.fn(() => ({
    push: jest.fn(),
    replace: jest.fn(),
    back: jest.fn(),
    forward: jest.fn(),
    refresh: jest.fn(),
    prefetch: jest.fn(),
  })),
  usePathname: jest.fn(() => '/'),
  useParams: jest.fn(() => ({})),
  useSearchParams: jest.fn(() => ({
    get: jest.fn(),
    getAll: jest.fn(),
    has: jest.fn(),
    entries: jest.fn(),
    forEach: jest.fn(),
    keys: jest.fn(),
    values: jest.fn(),
  })),
}))

// Mock framer-motion — covers the LazyMotion `m.` components, presence/config
// wrappers, and the standalone animate() used by useCountUp. Tests that need
// the REAL library (e.g. MotionProvider under LazyMotion strict) re-mock with
// jest.requireActual in their own file.
jest.mock('framer-motion', () => {
  const React = jest.requireActual('react')
  // Strip framer-only props so they never hit the DOM as unknown attributes.
  const MOTION_ONLY_PROPS = [
    'initial', 'animate', 'exit', 'transition', 'variants', 'layout',
    'layoutId', 'whileHover', 'whileTap', 'whileInView', 'viewport', 'drag',
    'dragConstraints', 'onAnimationStart', 'onAnimationComplete',
  ]
  const stripMotionProps = (props) => {
    const rest = { ...props }
    for (const key of MOTION_ONLY_PROPS) delete rest[key]
    return rest
  }
  const passthrough = (tag) =>
    function MockMotionComponent({ children, ...props }) {
      return React.createElement(tag, stripMotionProps(props), children)
    }
  const componentCache = {}
  const proxy = new Proxy({}, {
    get: (_target, tag) => {
      const key = String(tag)
      if (!componentCache[key]) componentCache[key] = passthrough(key)
      return componentCache[key]
    },
  })
  const Passthrough = ({ children }) => <>{children}</>
  return {
    motion: proxy,
    m: proxy,
    AnimatePresence: Passthrough,
    LazyMotion: Passthrough,
    MotionConfig: Passthrough,
    useReducedMotion: jest.fn(() => false),
    // Immediate-jump fake: report the target once and complete, returning
    // stoppable controls — deterministic for jsdom (no rAF timing).
    animate: jest.fn((from, to, options = {}) => {
      options.onUpdate?.(to)
      options.onComplete?.()
      return { stop: jest.fn() }
    }),
    domMax: {},
  }
})

// Radix primitives (Select, Dropdown, Popover) drive their open/close from Pointer
// Events and scroll the active item into view. jsdom implements neither, so a
// `<Select>` never opens and its items never render — which silently reduces any
// assertion about what a user can PICK to an assertion about nothing. #485 needs the
// open listbox (that is where a truncated list is actually visible to a vendor), so
// the three missing APIs are stubbed here rather than per file.
if (typeof window !== 'undefined') {
  if (!window.Element.prototype.hasPointerCapture) {
    window.Element.prototype.hasPointerCapture = function () {
      return false
    }
  }
  if (!window.Element.prototype.setPointerCapture) {
    window.Element.prototype.setPointerCapture = function () {}
  }
  if (!window.Element.prototype.releasePointerCapture) {
    window.Element.prototype.releasePointerCapture = function () {}
  }
  if (!window.Element.prototype.scrollIntoView) {
    window.Element.prototype.scrollIntoView = function () {}
  }
}

// Radix Checkbox renders a hidden native `BubbleInput` when it is inside a <form>, so that form
// semantics and validation work on a real input rather than on the styled button. That input sizes
// itself with `@radix-ui/react-use-size`, which constructs a ResizeObserver — an API jsdom does not
// implement at all. Without this stub the checkout's acknowledgement checkbox throws
// "ResizeObserver is not defined" during layout effects and the WHOLE page fails to render, which
// reads in the output as every checkout assertion failing rather than as one missing browser API.
//
// Stubbed here rather than per file for the same reason as the pointer-capture block above: the
// primitive is shared, and a per-file polyfill leaves the next consumer to rediscover it.
if (typeof globalThis !== 'undefined' && typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}

// Mock environment variables
process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080/api'
process.env.NEXTAUTH_URL = 'http://localhost:3000'
