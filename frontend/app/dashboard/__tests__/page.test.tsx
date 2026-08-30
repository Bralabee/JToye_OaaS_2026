import { render, screen, waitFor } from '@testing-library/react'
import DashboardPage from '../page'
import apiClient from '@/lib/api-client'
import { WIDTH_TIER_CLASS } from '@/components/layout/content-tier'
import { getShopContext } from '@/lib/shop-context'
import { manyShops, pagedResponse, param } from '@/test-utils/spring-page'

import React from 'react'

// Mock recharts — jsdom doesn't support SVG rendering
jest.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  PieChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Pie: () => <div />,
  Cell: () => <div />,
  BarChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Bar: () => <div />,
  XAxis: () => <div />,
  YAxis: () => <div />,
  CartesianGrid: () => <div />,
  Tooltip: () => <div />,
  Legend: () => <div />,
}))

// Mock the API client
jest.mock('@/lib/api-client')
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock('@/lib/shop-context', () => ({
  ALL_SHOPS_CONTEXT: 'all',
  getShopContext: jest.fn(() => 'all'),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))
const mockedGetShopContext = getShopContext as jest.MockedFunction<typeof getShopContext>

// Mock the toast hook
jest.mock('@/hooks/use-toast', () => ({
  useToast: () => ({
    toast: jest.fn(),
  }),
}))

// Mock ResizeObserver for recharts
global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const defaultMock = (url: string) => {
  if (url === '/api/v1/financial-transactions/summary') {
    return Promise.resolve({ data: { totalRevenuePennies: 0, totalExpensesPennies: 0, netAmountPennies: 0, totalVatPennies: 0, transactionCount: 0, vatBreakdown: [] } })
  }
  if (url.startsWith('/api/v1/onboarding/me')) {
    // Default: no onboarding yet (404) — the banner falls back to "not started".
    return Promise.reject({ response: { status: 404 } })
  }
  return Promise.resolve({ data: { content: [], totalElements: 0 } })
}

describe('Dashboard Page', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation(defaultMock as jest.Mock)
    mockedGetShopContext.mockReturnValue('all')
  })

  it('should render loading spinner initially', () => {
    mockedApiClient.get.mockImplementation(() => new Promise(() => {}))

    const { container } = render(<DashboardPage />)

    const spinner = container.querySelector('.animate-spin')
    expect(spinner).toBeInTheDocument()
    // Motion-uplift foundation: spinner follows the flame-orange brand primary
    expect(spinner).toHaveClass('border-orange-600')
  })

  it('should render dashboard heading after loading', async () => {
    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Dashboard')).toBeInTheDocument()
    })
  })

  it('should display welcome message', async () => {
    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText(/Welcome to your J'Toye OaaS management dashboard/i)).toBeInTheDocument()
    })
  })

  it('should fetch and display stats', async () => {
    mockedApiClient.get.mockImplementation((url: string) => {
      if (url === '/api/v1/financial-transactions/summary') return Promise.resolve({ data: { totalRevenuePennies: 0, totalExpensesPennies: 0, netAmountPennies: 0, totalVatPennies: 0, transactionCount: 0, vatBreakdown: [] } })
      return Promise.resolve({ data: { content: [], totalElements: 42 } })
    })

    render(<DashboardPage />)

    await waitFor(() => {
      const statsCards = screen.getAllByText('42')
      expect(statsCards.length).toBeGreaterThan(0)
    })
  })

  it('should display all stat card titles', async () => {
    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Shops')).toBeInTheDocument()
      expect(screen.getByText('Products')).toBeInTheDocument()
      expect(screen.getByText('Orders')).toBeInTheDocument()
      expect(screen.getByText('Customers')).toBeInTheDocument()
    })
  })

  it('should display "No orders yet" when there are no recent orders', async () => {
    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('No orders yet')).toBeInTheDocument()
      expect(screen.getByText('Orders will appear here once they are created')).toBeInTheDocument()
    })
  })

  it('should display recent orders table when orders exist', async () => {
    const mockOrders = [
      {
        id: '123e4567-e89b-12d3-a456-426614174000',
        tenantId: 'tenant1',
        shopId: 'shop1',
        status: 'PENDING' as const,
        customerName: 'John Doe',
        customerEmail: 'john@example.com',
        totalAmountPennies: 1999,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ]

    mockedApiClient.get.mockImplementation((url: string) => {
      if (url === '/api/v1/orders?size=10&sort=createdAt,desc') {
        return Promise.resolve({ data: { content: mockOrders, totalElements: 1 } })
      }
      if (url === '/api/v1/financial-transactions/summary') {
        return Promise.resolve({ data: { totalRevenuePennies: 0, totalExpensesPennies: 0, netAmountPennies: 0, totalVatPennies: 0, transactionCount: 0, vatBreakdown: [] } })
      }
      return Promise.resolve({ data: { content: [], totalElements: 5 } })
    })

    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('John Doe')).toBeInTheDocument()
      expect(screen.getByText('john@example.com')).toBeInTheDocument()
      expect(screen.getByText('£19.99')).toBeInTheDocument()
    })

    // QA-council F3 / A11Y-1: PENDING badge is white text on bg-yellow-700,
    // not the failing bg-yellow-500 (1.92:1 on white).
    expect(screen.getByText('Pending')).toHaveClass('bg-yellow-700')
    expect(screen.getByText('Pending')).not.toHaveClass('bg-yellow-500')
  })

  it('should make API calls to fetch dashboard data', async () => {
    render(<DashboardPage />)

    await waitFor(() => {
      // The overview needs the shop NAMES to label the active shop-context, so it
      // reads the whole list. #485: this used to be a single '/api/v1/shops?size=100'
      // whose first page was taken for the whole list; it is now a ?page=&size= walk
      // via fetchAllMyShops, so the assertion moved from an exact URL to the shape.
      expect(mockedApiClient.get).toHaveBeenCalledWith(
        expect.stringMatching(/^\/api\/v1\/shops\?page=0&size=\d+$/)
      )
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/products?size=1')
      // No shop selected in jsdom (empty localStorage) → order calls carry no ?shopId=.
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/orders?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/customers?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/orders?size=10&sort=createdAt,desc')
      // Issue #95: server caps page size at 100 — the dashboard must not over-ask.
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/orders?size=100&sort=createdAt,desc')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/financial-transactions/summary')
    })
  })

  it('shows the incomplete-onboarding banner while onboarding is not LIVE', async () => {
    mockedApiClient.get.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/onboarding/me')) {
        return Promise.resolve({ data: { id: 'onb-1', status: 'VERIFYING', gates: [] } })
      }
      if (url === '/api/v1/financial-transactions/summary') {
        return Promise.resolve({ data: { totalRevenuePennies: 0, totalExpensesPennies: 0, netAmountPennies: 0, totalVatPennies: 0, transactionCount: 0, vatBreakdown: [] } })
      }
      return Promise.resolve({ data: { content: [], totalElements: 0 } })
    })

    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Your onboarding is in progress.')).toBeInTheDocument()
    })
    const link = screen.getByRole('link', { name: /view status/i })
    expect(link).toHaveAttribute('href', '/dashboard/onboarding')
  })

  describe('#485 call site :170 — the shop list is followed to its end', () => {
    // 150 shops: genuinely more than the server's clamped 100-row page, so the
    // tail lives on page 1 and only paged code can reach it.
    const SHOPS = manyShops(150)
    const TAIL = SHOPS[SHOPS.length - 1]

    const pagedShopsMock = (url: string) => {
      if (url.startsWith('/api/v1/shops')) return Promise.resolve(pagedResponse(url, SHOPS))
      return defaultMock(url)
    }

    it('names a shop that lives past the first page in the context header', async () => {
      // THE USER-VISIBLE LOSS. `contextShopName` looks the switcher's shop up in the
      // fetched list and falls back to the generic "the selected shop" when it is not
      // there. With one truncating request, every shop past the 100th was permanently
      // unnameable on their own dashboard.
      mockedGetShopContext.mockReturnValue(TAIL.id)
      mockedApiClient.get.mockImplementation(pagedShopsMock as jest.Mock)

      render(<DashboardPage />)

      await waitFor(() =>
        expect(screen.getByText(new RegExp(`Viewing ${TAIL.name} `))).toBeInTheDocument()
      )
      expect(screen.queryByText(/Viewing the selected shop/)).not.toBeInTheDocument()
    })

    it('requests page 0 AND page 1 rather than one page of 100', async () => {
      mockedApiClient.get.mockImplementation(pagedShopsMock as jest.Mock)

      render(<DashboardPage />)

      await waitFor(() => {
        const pages = mockedApiClient.get.mock.calls
          .map(([u]) => String(u))
          .filter((u) => u.startsWith('/api/v1/shops'))
          .map((u) => param(u, 'page'))
        expect(pages).toEqual(['0', '1'])
      })
    })

    it('counts every shop on the Shops stat card, not the first page of them', async () => {
      mockedApiClient.get.mockImplementation(pagedShopsMock as jest.Mock)

      render(<DashboardPage />)

      await waitFor(() => expect(screen.getByText('Shops')).toBeInTheDocument())
      // The count animates up via useCountUp, so wait for it to settle on 150 —
      // never on 100, which is what a single clamped page would have reported.
      await waitFor(() => expect(screen.getByText('150')).toBeInTheDocument())
    })
  })

  it('hides the banner when onboarding is LIVE', async () => {
    mockedApiClient.get.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/onboarding/me')) {
        return Promise.resolve({ data: { id: 'onb-1', status: 'LIVE', gates: [] } })
      }
      if (url === '/api/v1/financial-transactions/summary') {
        return Promise.resolve({ data: { totalRevenuePennies: 0, totalExpensesPennies: 0, netAmountPennies: 0, totalVatPennies: 0, transactionCount: 0, vatBreakdown: [] } })
      }
      return Promise.resolve({ data: { content: [], totalElements: 0 } })
    })

    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Dashboard')).toBeInTheDocument()
    })
    expect(screen.queryByText('Your onboarding is in progress.')).not.toBeInTheDocument()
    expect(screen.queryByText('Finish setting up your shop to go live.')).not.toBeInTheDocument()
  })
})

// QA-council A11Y-6: the page renders an <h1> ("Dashboard") followed
// immediately by CardTitle's hard-coded <h3> (the first stat card, "Shops")
// — no <h2> anywhere in between, which is a skipped heading level (axe
// heading-order).
describe('Dashboard Page — heading hierarchy (QA-council A11Y-6)', () => {
  function headingLevels(): number[] {
    return screen.getAllByRole('heading').map((el) => Number(el.tagName.slice(1)))
  }

  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation(defaultMock as jest.Mock)
    mockedGetShopContext.mockReturnValue('all')
  })

  it('never steps DOWN more than one level at a time (no H1 -> H3 skip)', async () => {
    render(<DashboardPage />)
    await waitFor(() => expect(screen.getByText('Shops')).toBeInTheDocument())

    const levels = headingLevels()
    // POSITIVE CONTROL: the page genuinely has more than one heading level to
    // check — an empty/blind scan would pass this assertion vacuously.
    expect(levels.length).toBeGreaterThan(3)
    expect(levels[0]).toBe(1)
    for (let i = 1; i < levels.length; i++) {
      if (levels[i] > levels[i - 1]) {
        expect(levels[i] - levels[i - 1]).toBe(1)
      }
    }
  })
})

/**
 * Phase 35 — the Index width tier (ORCH-03, orchestrator decision 2026-08-29).
 *
 * The overview is tiered Index rather than Detail on purpose: its recent-orders
 * table is the same six-column shape as /dashboard/orders, and showing one table
 * at two different widths on two pages is exactly the half-shipped inconsistency
 * this phase exists to remove.
 *
 * PATTERNS.md F-3: a measurement at the band width cannot distinguish a
 * deliberate resource-index tier from a forgotten cap — both read identically.
 * The declaration is what makes the uncapped claim falsifiable.
 */
describe('Dashboard overview — the Index width tier (phase 35)', () => {
  // A Tailwind max-width utility as a whole class TOKEN, never a substring.
  const WIDTH_CAP = /(?:^|\s)max-w-/

  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation(defaultMock as jest.Mock)
    mockedGetShopContext.mockReturnValue('all')
  })

  async function loadedRoot() {
    const view = render(<DashboardPage />)
    // Wait for the LOADED branch — the in-flight branch is the spinner guard,
    // which carries no tier by design (see the note at the declaration site).
    await screen.findByRole('heading', { name: 'Dashboard', level: 1 })
    const declared = view.container.querySelectorAll<HTMLElement>('[data-width-tier]')
    return { view, declared }
  }

  it('declares the index tier once, on the loaded root element itself', async () => {
    const { view, declared } = await loadedRoot()
    // Exactly one: a nested second declaration would be a cap inside a cap.
    expect(declared).toHaveLength(1)
    expect(declared[0]).toBe(view.container.firstElementChild)
    expect(declared[0]).toHaveAttribute('data-width-tier', 'index')
  })

  it('adds no width cap of its own — Index is fluid to the shell band', async () => {
    const { declared } = await loadedRoot()

    // NON-VACUITY CONTROL: the detector fires on a real tier cap read from the
    // vocabulary module, so the absence below is about this element and not
    // about a pattern incapable of matching anything.
    const probe = document.createElement('div')
    probe.className = `space-y-8 ${WIDTH_TIER_CLASS.detail}`
    expect(probe.className).toMatch(WIDTH_CAP)

    expect(declared[0].className).not.toMatch(WIDTH_CAP)
  })

  it('keeps the vertical rhythm class the declaration was added beside', async () => {
    const { declared } = await loadedRoot()
    expect(declared[0]).toHaveClass('space-y-8')
  })
})
