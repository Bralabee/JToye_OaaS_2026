/**
 * MotionProvider — LazyMotion strict (async domMax) + MotionConfig
 * reducedMotion="user" must render its children.
 */

// Real framer-motion for this file (overrides the global jest.setup mock).
jest.mock("framer-motion", () => jest.requireActual("framer-motion"))

import { render, screen, act } from "@testing-library/react"
import { MotionProvider } from "@/components/motion-provider"

// jsdom has no matchMedia — MotionConfig reducedMotion="user" queries it.
beforeAll(() => {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: jest.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      dispatchEvent: jest.fn(),
    })),
  })
})

describe("MotionProvider", () => {
  it("renders its children", async () => {
    render(
      <MotionProvider>
        <div data-testid="child">hello</div>
      </MotionProvider>
    )
    // Flush the async LazyMotion feature load.
    await act(async () => {})
    expect(screen.getByTestId("child")).toHaveTextContent("hello")
  })
})
