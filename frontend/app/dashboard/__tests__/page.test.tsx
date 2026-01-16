import { render, screen, waitFor } from '@testing-library/react'
import DashboardPage from '../page'
import apiClient from '@/lib/api-client'

// Mock the API client
jest.mock('@/lib/api-client')
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

// Mock the toast hook
jest.mock('@/hooks/use-toast', () => ({
  useToast: () => ({
    toast: jest.fn(),
  }),
}))

describe('Dashboard Page', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('should render loading spinner initially', () => {
    mockedApiClient.get.mockImplementation(() => new Promise(() => {}))

    const { container } = render(<DashboardPage />)

    const spinner = container.querySelector('.animate-spin')
    expect(spinner).toBeInTheDocument()
    expect(spinner).toHaveClass('border-blue-600')
  })

  it('should render dashboard heading after loading', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Dashboard')).toBeInTheDocument()
    })
  })

  it('should display welcome message', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText(/Welcome to your J'Toye OaaS management dashboard/i)).toBeInTheDocument()
    })
  })

  it('should fetch and display stats', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 42,
      },
    })

    render(<DashboardPage />)

    await waitFor(() => {
      const statsCards = screen.getAllByText('42')
      expect(statsCards.length).toBeGreaterThan(0)
    })
  })

  it('should display all stat card titles', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

    render(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Shops')).toBeInTheDocument()
      expect(screen.getByText('Products')).toBeInTheDocument()
      expect(screen.getByText('Orders')).toBeInTheDocument()
      expect(screen.getByText('Customers')).toBeInTheDocument()
    })
  })

  it('should display "No orders yet" when there are no recent orders', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

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
        totalPricePennies: 1999,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ]

    let callCount = 0
    mockedApiClient.get.mockImplementation(() => {
      callCount++
      if (callCount === 5) {
        // Last call is for recent orders
        return Promise.resolve({ data: { content: mockOrders, totalElements: 1 } })
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
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

    render(<DashboardPage />)

    await waitFor(() => {
      expect(mockedApiClient.get).toHaveBeenCalledWith('/shops?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/products?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/orders?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/customers?size=1')
      expect(mockedApiClient.get).toHaveBeenCalledWith('/orders?size=10&sort=createdAt,desc')
    })
  })
})
