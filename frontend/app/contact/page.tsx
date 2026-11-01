import type { Metadata } from "next"
import Link from "next/link"
import {
  getCompanyInfo,
  resolveControllerContact,
  resolvePublicContact,
} from "@/lib/company"
import { OPERATOR_APPLICATION_REVIEW_LINE } from "@/lib/operator-copy"
import { PublicShell } from "@/components/public/public-shell"
import { LEGAL_LINK_CLASS } from "@/components/legal/policy-page"

export const metadata: Metadata = {
  title: "Contact J'Toye",
  description:
    "How to contact J'Toye Digital Ltd, the operator of the J'Toye platform, and where to find the details of the shop you ordered from.",
  alternates: { canonical: "/contact" },
}

/**
 * The platform's contact route (SW-CLR-04, swarm epic #964).
 *
 * The 2026-10-19 persona swarm found no contact or sales route anywhere on the
 * public site: "Start your application" on /for-operators ends at a sign-in,
 * and no public marketing surface carried a single mailto: or tel:. This page
 * is the destination the footer and /for-operators now link to.
 *
 * THE LEGAL HUB'S LAYOUT, NOT A NEW ONE. Markup and classes are app/legal/
 * page.tsx's — PublicShell, the same heading, standfirst and definition list —
 * so no new visual design is introduced.
 *
 * NOTHING IS HARDCODED. The email and phone come from NEXT_PUBLIC_CONTACT_EMAIL
 * and NEXT_PUBLIC_CONTACT_PHONE through resolvePublicContact(), and each is
 * rendered only when set. A deployment that sets neither says so in plain
 * words rather than rendering a term with nothing after it. The company facts
 * are lib/company.ts's; the registered office and data-protection contact go
 * through resolveControllerContact() exactly as on /legal.
 *
 * Platform-owned (J'Toye Digital Ltd), never a vendor storefront: the identity
 * below must not be rendered under a vendor's brand (lib/company.ts header).
 */


export default function ContactPage() {
  const c = getCompanyInfo()
  const controller = resolveControllerContact(c)
  const contact = resolvePublicContact(c)
  return (
    <PublicShell>
      <div className="mx-auto max-w-3xl px-4 py-16 sm:px-6 lg:px-8">
        <h1 className="text-[28px] font-semibold leading-tight tracking-tight text-oxblood">
          Contact J&apos;Toye
        </h1>
        <p className="mt-4 text-base leading-relaxed text-slate-600">
          The J&apos;Toye platform is operated by the company below. For a
          question about an order, contact the shop you ordered from: its
          details are shown on the shop page, at checkout and on your order
          confirmation.
        </p>

        <dl className="mt-8 space-y-4 text-base leading-relaxed">
          <div>
            <dt className="font-semibold text-oxblood">Registered company name</dt>
            <dd className="text-slate-600">{c.legalName}</dd>
          </div>
          <div>
            <dt className="font-semibold text-oxblood">Company number</dt>
            <dd className="text-slate-600">{c.companyNumber}</dd>
          </div>
          <div>
            <dt className="font-semibold text-oxblood">Place of registration</dt>
            <dd className="text-slate-600">{c.registrationJurisdiction}</dd>
          </div>
          {controller.postal ? (
            <div>
              <dt className="font-semibold text-oxblood">Registered office</dt>
              <dd className="text-slate-600">{controller.postal}</dd>
            </div>
          ) : null}
          {contact.email && contact.emailHref ? (
            <div>
              <dt className="font-semibold text-oxblood">Email</dt>
              <dd>
                <a href={contact.emailHref} className={LEGAL_LINK_CLASS}>
                  {contact.email}
                </a>
              </dd>
            </div>
          ) : null}
          {contact.phone && contact.phoneHref ? (
            <div>
              <dt className="font-semibold text-oxblood">Phone</dt>
              <dd>
                <a href={contact.phoneHref} className={LEGAL_LINK_CLASS}>
                  {contact.phone}
                </a>
              </dd>
            </div>
          ) : null}
          {controller.email && controller.emailHref ? (
            <div>
              <dt className="font-semibold text-oxblood">
                Data protection contact
              </dt>
              <dd>
                <a href={controller.emailHref} className={LEGAL_LINK_CLASS}>
                  {controller.email}
                </a>
              </dd>
            </div>
          ) : null}
        </dl>

        {contact.anyRoute ? null : (
          <p className="mt-8 text-base leading-relaxed text-slate-600">
            J&apos;Toye has not yet published a general contact email address
            or phone number. For anything about your personal data, use the
            routes in the{" "}
            <Link href="/legal/privacy" className={LEGAL_LINK_CLASS}>
              privacy notice
            </Link>
            .
          </p>
        )}

        <h2 className="mt-14 text-xl font-semibold tracking-tight text-oxblood">
          Food businesses
        </h2>
        <p className="mt-4 text-base leading-relaxed text-slate-600">
          J&apos;Toye runs an assisted pilot for food businesses.{" "}
          {OPERATOR_APPLICATION_REVIEW_LINE} See the{" "}
          <Link href="/for-operators" className={LEGAL_LINK_CLASS}>
            operator page
          </Link>{" "}
          for the pilot and its pricing.
        </p>

        <p className="mt-14 text-base">
          <Link
            href="/legal"
            className={`inline-flex min-h-11 items-center ${LEGAL_LINK_CLASS}`}
          >
            ← Legal &amp; company information
          </Link>
        </p>
      </div>
    </PublicShell>
  )
}
