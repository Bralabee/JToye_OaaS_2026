import { render, screen, fireEvent } from '@testing-library/react'
import { signIn } from 'next-auth/react'
import SignInPage from '../page'

// Mock next-auth already done in jest.setup.js

describe('SignIn Page', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('should render the sign-in page with the shipped brand', () => {
    render(<SignInPage />)

    expect(screen.getByText("J'Toye")).toBeInTheDocument()
    expect(screen.getByText('Welcome back')).toBeInTheDocument()
    expect(
      screen.getByText('Sign in to manage your shop, orders and kitchen.')
    ).toBeInTheDocument()
  })

  it('should link the brand wordmark to the public home page', () => {
    render(<SignInPage />)

    const brandLink = screen.getByRole('link', { name: /j'toye home/i })
    expect(brandLink).toHaveAttribute('href', '/')
  })

  it('should display the Keycloak sign-in button', () => {
    render(<SignInPage />)

    const signInButton = screen.getByRole('button', { name: /sign in with keycloak/i })
    expect(signInButton).toBeInTheDocument()
  })

  it('should call signIn when button is clicked', () => {
    render(<SignInPage />)

    const signInButton = screen.getByRole('button', { name: /sign in with keycloak/i })
    fireEvent.click(signInButton)

    expect(signIn).toHaveBeenCalledWith('keycloak', { callbackUrl: '/dashboard' })
  })

  it('should display security message', () => {
    render(<SignInPage />)

    expect(screen.getByText('Secure authentication via Keycloak OIDC')).toBeInTheDocument()
  })

  // Navigation-trap regression guard: this page is a landing destination
  // (expired sessions, /dashboard deep links) and must NEVER be a dead end.
  it('should provide an escape link back to the public home page', () => {
    render(<SignInPage />)

    const backLink = screen.getByRole('link', { name: /back to j'toye/i })
    expect(backLink).toHaveAttribute('href', '/')
  })

  it('should provide an escape link to browse kitchens', () => {
    render(<SignInPage />)

    const browseLink = screen.getByRole('link', { name: /browse kitchens/i })
    expect(browseLink).toHaveAttribute('href', '/shop')
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
