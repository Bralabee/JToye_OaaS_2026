import { render, screen, waitFor } from '@testing-library/react'
import DashboardPage from '../page'
import apiClient from '@/lib/api-client'

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
  return Promise.resolve({ data: { content: [], totalElements: 0 } })
}

describe('Dashboard Page', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation(defaultMock as jest.Mock)
  })

  it('should render loading spinner initially', () => {
    mockedApiClient.get.mockImplementation(() => new Promise(() => {}))

    const { container } = render(<DashboardPage />)

    const spinner = container.querySelector('.animate-spin')
    expect(spinner).toBeInTheDocument()
    expect(spinner).toHaveClass('border-blue-600')
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
  })

  it('should make API calls to fetch dashboard data', async () => {
    render(<DashboardPage />)

    await waitFor(() => {
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/shops?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/products?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/orders?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/customers?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/orders?size=10&sort=createdAt,desc')
      // Issue #95: server caps page size at 100 — the dashboard must not over-ask.
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/orders?size=100&sort=createdAt,desc')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/api/v1/financial-transactions/summary')
    })
  })
})
