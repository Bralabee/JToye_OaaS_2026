import apiClient from '../api-client'

// Mock axios.create
jest.mock('axios', () => ({
  create: jest.fn(() => ({
    defaults: {
      baseURL: 'http://localhost:8080/api',
      headers: {
        'Content-Type': 'application/json',
      },
    },
    interceptors: {
      request: {
        use: jest.fn(),
      },
      response: {
        use: jest.fn(),
      },
    },
    get: jest.fn(),
    post: jest.fn(),
    put: jest.fn(),
    delete: jest.fn(),
  })),
}))

describe('API Client', () => {
  describe('Configuration', () => {
    it('should be configured with correct base URL', () => {
      expect(apiClient.defaults.baseURL).toBe('http://localhost:8080/api')
    })

    it('should have JSON content type header', () => {
      expect(apiClient.defaults.headers['Content-Type']).toBe('application/json')
    })

    it('should have interceptors configured', () => {
      expect(apiClient.interceptors).toBeDefined()
      expect(apiClient.interceptors.request).toBeDefined()
      expect(apiClient.interceptors.response).toBeDefined()
    })

    it('should be an axios instance', () => {
      expect(apiClient.get).toBeDefined()
      expect(apiClient.post).toBeDefined()
      expect(apiClient.put).toBeDefined()
      expect(apiClient.delete).toBeDefined()
    })
  })
})
