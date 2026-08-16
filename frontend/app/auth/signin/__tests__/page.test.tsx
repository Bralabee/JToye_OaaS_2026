import { render, screen, fireEvent, within } from '@testing-library/react'
import { signIn } from 'next-auth/react'
import SignInPage from '../page'
import { metadata } from '../layout'

// Mock next-auth already done in jest.setup.js

describe('SignIn Page', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('should render the sign-in page with the shipped brand', () => {
    render(<SignInPage />)

    expect(screen.getByText("J'Toye")).toBeInTheDocument()
    // Retitled from the persona-neutral "Welcome back". This page authenticates
    // against the jtoye-dev STAFF realm only, so a shopper who arrives cannot sign
    // in here at all — the heading has to say whose page it is.
    expect(screen.getByText('Vendor sign in')).toBeInTheDocument()
    expect(
      screen.getByText(
        'For kitchen operators and staff — manage your shop, orders and fulfilment.'
      )
    ).toBeInTheDocument()
  })

  // The persona cross-link. Customers and vendors are separate Keycloak realms, so
  // arriving on the wrong page is unrecoverable without a visible route to the
  // other one — the realm simply refuses an account it has never heard of, with no
  // explanation the user can act on.
  it('should offer customers a route to their own sign-in page', () => {
    render(<SignInPage />)

    const customerLink = screen.getByRole('link', { name: /customer sign in/i })
    expect(customerLink).toHaveAttribute('href', '/shop/signin')
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

/**
 * F-D — the landmark and heading structure this page had none of.
 *
 * Measured 2026-08-15 on the pre-fix tree: main=0, banner=0, contentinfo=0,
 * h1=0, and 7 of the 15 remaining axe nodes across ALL declared surfaces were on
 * this one page (landmark-one-main:1, page-has-heading-one:1, region:5).
 *
 * The `region` half is asserted as CONTAINMENT, not as a count. axe reports it
 * as "content outside a landmark", so the honest assertion is that each piece of
 * content is inside the landmark — a bare `getByRole("main")` would pass on a
 * build where the main existed but the card sat beside it, which is exactly the
 * shape that moves a region node instead of closing it.
 */
describe('SignIn Page landmarks and heading (F-D)', () => {
  it('exposes exactly one main landmark, carrying the skip-link target id', () => {
    render(<SignInPage />)

    const mains = screen.getAllByRole('main')
    expect(mains).toHaveLength(1)
    expect(mains[0]).toHaveAttribute('id', 'main')
  })

  it('exposes exactly one level-1 heading, and it names the page', () => {
    render(<SignInPage />)

    const h1s = screen.getAllByRole('heading', { level: 1 })
    expect(h1s).toHaveLength(1)
    expect(h1s[0]).toHaveTextContent(/vendor sign in/i)
    // A second competing heading is the regression this catches: promoting the
    // card title while ALSO leaving a heading behind would satisfy a
    // "page has an h1" check and still be wrong.
    expect(h1s[0].tagName).toBe('H1')
  })

  it('opens with a skip link that is the first link in document order', () => {
    const { container } = render(<SignInPage />)

    const links = Array.from(container.querySelectorAll('a'))
    // Control: the page rendered its links at all, so the index below is a
    // statement about ordering and not about an empty list.
    expect(links.length).toBeGreaterThan(3)
    expect(links[0]).toHaveAttribute('href', '#main')
    expect(links[0]).toHaveTextContent(/skip to main content/i)
    expect(links[0]).toHaveClass('sr-only')
    expect(links[0]).toHaveClass('focus:not-sr-only')
  })

  it('puts every piece of page content inside the landmark (region)', () => {
    render(<SignInPage />)
    const main = screen.getByRole('main')

    // The card...
    expect(within(main).getByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(
      within(main).getByRole('button', { name: /sign in with keycloak/i })
    ).toBeInTheDocument()
    // ...the escape links...
    expect(within(main).getByRole('link', { name: /customer sign in/i })).toBeInTheDocument()
    expect(within(main).getByRole('link', { name: /back to j'toye/i })).toBeInTheDocument()
    expect(within(main).getByRole('link', { name: /browse kitchens/i })).toBeInTheDocument()
    // ...and the trading disclosure, which sat outside the card and was the
    // easiest of the five region nodes to leave behind.
    expect(within(main).getByText(/company no\. 16471464/i)).toBeInTheDocument()
  })
})

/**
 * Incremental Betterment: what this page is NOT allowed to lose.
 *
 * The file's own header calls the escape links, the realm-split copy and the
 * brand treatment load-bearing. Regression by omission is a defect even when the
 * accessibility count improves, so the preservation arm is asserted next to the
 * improvement arm rather than trusted.
 */
describe('SignIn Page preserves every load-bearing element', () => {
  it('still renders the wordmark, both escape links, the cross-link and the legal line', () => {
    render(<SignInPage />)

    expect(screen.getByRole('link', { name: /j'toye home/i })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: /customer sign in/i })).toHaveAttribute(
      'href',
      '/shop/signin'
    )
    expect(screen.getByRole('link', { name: /back to j'toye/i })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: /browse kitchens/i })).toHaveAttribute('href', '/shop')
    expect(screen.getByText(/company no\. 16471464/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /legal & company information/i })).toHaveAttribute(
      'href',
      '/legal'
    )
    // The realm-split copy — the one thing that tells a shopper why their
    // password will not work here.
    expect(
      screen.getByText(/for kitchen operators and staff/i)
    ).toBeInTheDocument()
  })
})

/**
 * The page title. A `"use client"` page cannot export metadata, so the title
 * comes from the sibling layout — and until it existed this route served the
 * ROOT default. The negative assertion is the one that matters: a positive-only
 * check would pass on a build that appended a good title to a stale template.
 */
describe('SignIn Page metadata (own title, not the root default)', () => {
  it('names the page and the persona', () => {
    expect(String(metadata.title)).toMatch(/vendor sign in/i)
  })

  it('no longer serves the root default title', () => {
    expect(String(metadata.title)).not.toMatch(/Multi-Tenant Order Management/i)
  })

  it('declares its own canonical', () => {
    expect(metadata.alternates?.canonical).toBe('/auth/signin')
  })

  it('describes the customer door without disclosing realm topology', () => {
    const description = String(metadata.description)
    expect(description).toMatch(/customer/i)
    // T-31-03-01: metadata is served to anyone who asks, crawlers included.
    expect(description).not.toMatch(/jtoye-dev|jtoye-customers|keycloak|realm/i)
  })
})
