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

// Mock framer-motion
jest.mock('framer-motion', () => {
  const React = require('react')
  // Proxy that returns a stub component for any HTML tag (motion.div, motion.main, motion.span, ...)
  // Strips motion-only props so React doesn't warn about unknown DOM attributes.
  const MOTION_ONLY_PROPS = new Set([
    'initial', 'animate', 'exit', 'variants', 'transition', 'whileHover',
    'whileTap', 'whileFocus', 'whileInView', 'layout', 'layoutId', 'drag',
    'dragConstraints', 'dragElastic', 'dragMomentum', 'onAnimationStart',
    'onAnimationComplete', 'viewport', 'custom',
  ])
  const stripMotionProps = (props) => {
    const out = {}
    for (const key of Object.keys(props)) {
      if (!MOTION_ONLY_PROPS.has(key)) out[key] = props[key]
    }
    return out
  }
  const motion = new Proxy({}, {
    get: (_target, tag) => {
      const Comp = React.forwardRef(({ children, ...props }, ref) =>
        React.createElement(tag, { ...stripMotionProps(props), ref }, children)
      )
      Comp.displayName = `motion.${String(tag)}`
      return Comp
    },
  })
  return {
    motion,
    AnimatePresence: ({ children }) => children,
    useReducedMotion: () => false,
    useMotionValue: (v) => ({ get: () => v, set: () => {} }),
    useTransform: () => ({ get: () => 0, set: () => {} }),
  }
})

// Mock environment variables
process.env.NEXT_PUBLIC_API_URL = 'http://localhost:8080/api'
process.env.NEXTAUTH_URL = 'http://localhost:3000'
