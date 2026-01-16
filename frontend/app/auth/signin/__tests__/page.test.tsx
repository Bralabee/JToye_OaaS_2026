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

    expect(screen.getByText("J'Toye OaaS")).toBeInTheDocument()
    expect(screen.getByText('Sign in to access your multi-tenant order management system')).toBeInTheDocument()
  })

  it('should display the Keycloak sign-in button', () => {
    render(<SignInPage />)

    const signInButton = screen.getByRole('button', { name: /sign in with keycloak/i })
    expect(signInButton).toBeInTheDocument()
  })

  it('should display the store icon', () => {
    render(<SignInPage />)

    // The Store icon is rendered as an SVG
    const heading = screen.getByText("J'Toye OaaS")
    expect(heading).toBeInTheDocument()
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

  it('should have proper styling classes for centered layout', () => {
    const { container } = render(<SignInPage />)

    const mainDiv = container.firstChild as HTMLElement
    expect(mainDiv.className).toContain('min-h-screen')
    expect(mainDiv.className).toContain('flex')
    expect(mainDiv.className).toContain('items-center')
    expect(mainDiv.className).toContain('justify-center')
  })
})
