import { render, screen, fireEvent } from '@testing-library/react'
import { signIn } from 'next-auth/react'
import SignInPage from '../page'

// Mock next-auth already done in jest.setup.js

describe('SignIn Page', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('should render the sign-in page', () => {
    render(<SignInPage />)

    expect(screen.getByText(/Welcome to J'Toye/i)).toBeInTheDocument()
    expect(
      screen.getByText('Sign in to manage your shops, products, and orders.'),
    ).toBeInTheDocument()
  })

  it('should display the Keycloak sign-in button', () => {
    render(<SignInPage />)

    const signInButton = screen.getByRole('button', { name: /sign in with keycloak/i })
    expect(signInButton).toBeInTheDocument()
  })

  it('should display the brand mark', () => {
    render(<SignInPage />)

    // The brand mark is rendered as an <img> with alt text
    const mark = screen.getByAltText(/J'Toye mark/i)
    expect(mark).toBeInTheDocument()
  })

  it('should call signIn when button is clicked', () => {
    render(<SignInPage />)

    const signInButton = screen.getByRole('button', { name: /sign in with keycloak/i })
    fireEvent.click(signInButton)

    expect(signIn).toHaveBeenCalledWith('keycloak', { callbackUrl: '/dashboard' })
  })

  it('should display the brand tagline', () => {
    render(<SignInPage />)

    expect(
      screen.getByText(/Every shop\. Every order\. One kitchen\./i),
    ).toBeInTheDocument()
  })

  it('should have proper styling classes for centered layout', () => {
    const { container } = render(<SignInPage />)

    const mainDiv = container.firstChild as HTMLElement
    expect(mainDiv.className).toContain('min-h-screen')
    expect(mainDiv.className).toContain('flex')
    expect(mainDiv.className).toContain('items-center')
    expect(mainDiv.className).toContain('justify-center')
  })
})
