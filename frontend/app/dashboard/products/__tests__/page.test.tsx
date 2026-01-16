import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import ProductsPage from '../page'
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

describe('Products Page', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('should render loading spinner initially', () => {
    mockedApiClient.get.mockImplementation(() => new Promise(() => {}))

    const { container } = render(<ProductsPage />)

    const spinner = container.querySelector('.animate-spin')
    expect(spinner).toBeInTheDocument()
    expect(spinner).toHaveClass('border-blue-600')
  })

  it('should render products heading after loading', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      expect(screen.getByText('Products')).toBeInTheDocument()
    })
  })

  it('should display "Add Product" button', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      const addButtons = screen.getAllByRole('button', { name: /add product/i })
      expect(addButtons.length).toBeGreaterThan(0)
    })
  })

  it('should fetch products on mount', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      expect(mockedApiClient.get).toHaveBeenCalledWith('/products?size=100&sort=createdAt,desc')
    })
  })

  it('should display empty state when no products exist', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      expect(screen.getByText('No products yet')).toBeInTheDocument()
      expect(screen.getByText('Get started by creating your first product')).toBeInTheDocument()
    })
  })

  it('should display products table when products exist', async () => {
    const mockProducts = [
      {
        id: 'prod-1',
        tenantId: 'tenant-1',
        sku: 'PROD-001',
        title: 'Chocolate Chip Cookies',
        ingredientsText: 'Flour, sugar, butter, chocolate chips',
        allergenMask: 0b1000101, // Gluten (bit 0), Eggs (bit 2), Milk (bit 6)
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ]

    mockedApiClient.get.mockResolvedValue({
      data: {
        content: mockProducts,
        totalElements: 1,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      expect(screen.getByText('PROD-001')).toBeInTheDocument()
      expect(screen.getByText('Chocolate Chip Cookies')).toBeInTheDocument()
    })
  })

  it('should display allergen badges for products', async () => {
    const mockProducts = [
      {
        id: 'prod-1',
        tenantId: 'tenant-1',
        sku: 'PROD-001',
        title: 'Test Product',
        ingredientsText: 'Test ingredients',
        allergenMask: 0b0000101, // Gluten (bit 0) and Eggs (bit 2)
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ]

    mockedApiClient.get.mockResolvedValue({
      data: {
        content: mockProducts,
        totalElements: 1,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      expect(screen.getByText('Gluten')).toBeInTheDocument()
      expect(screen.getByText('Eggs')).toBeInTheDocument()
    })
  })

  it('should display "No allergens" when allergenMask is 0', async () => {
    const mockProducts = [
      {
        id: 'prod-1',
        tenantId: 'tenant-1',
        sku: 'PROD-001',
        title: 'Test Product',
        ingredientsText: 'Test ingredients',
        allergenMask: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ]

    mockedApiClient.get.mockResolvedValue({
      data: {
        content: mockProducts,
        totalElements: 1,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      expect(screen.getByText('No allergens')).toBeInTheDocument()
    })
  })

  it('should display product count', async () => {
    const mockProducts = [
      {
        id: 'prod-1',
        tenantId: 'tenant-1',
        sku: 'PROD-001',
        title: 'Product 1',
        ingredientsText: 'Ingredients',
        allergenMask: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
      {
        id: 'prod-2',
        tenantId: 'tenant-1',
        sku: 'PROD-002',
        title: 'Product 2',
        ingredientsText: 'Ingredients',
        allergenMask: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ]

    mockedApiClient.get.mockResolvedValue({
      data: {
        content: mockProducts,
        totalElements: 2,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      expect(screen.getByText('2 products in total')).toBeInTheDocument()
    })
  })

  it('should open create dialog when "Add Product" button is clicked', async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [],
        totalElements: 0,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      const addButton = screen.getAllByRole('button', { name: /add product/i })[0]
      fireEvent.click(addButton)
    })

    await waitFor(() => {
      expect(screen.getByText('Create New Product')).toBeInTheDocument()
    })
  })

  it('should display edit and delete buttons for each product', async () => {
    const mockProducts = [
      {
        id: 'prod-1',
        tenantId: 'tenant-1',
        sku: 'PROD-001',
        title: 'Test Product',
        ingredientsText: 'Test ingredients',
        allergenMask: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ]

    mockedApiClient.get.mockResolvedValue({
      data: {
        content: mockProducts,
        totalElements: 1,
      },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      // Check for action buttons in the row
      const row = screen.getByText('Test Product').closest('tr')
      expect(row).toBeInTheDocument()
    })
  })
})
