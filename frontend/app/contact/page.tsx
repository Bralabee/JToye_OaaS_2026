import type { Metadata } from "next"
import Link from "next/link"
import {
  getCompanyInfo,
  resolveControllerContact,
  resolvePublicContact,
} from "@/lib/company"
import { OPERATOR_APPLICATION_REVIEW_LINE } from "@/lib/operator-copy"
import { POSITIONING_LINE } from "@/lib/positioning"
import { PublicShell } from "@/components/public/public-shell"
import { LEGAL_LINK_CLASS, LEGAL_PROSE_LINK_CLASS } from "@/components/legal/policy-page"

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

        {/* Issue 803 (UXT-031): the destination of "Start your application" on
            /for-operators, /pricing, /about. It used to be a Keycloak sign-in with
            no register link. There is no self-serve vendor sign-up (a person
            reviews every application; vendor-realm registration stays off), so the
            way in is a message to a person. The mailto and tel routes come from
            the same configuration as the list above and are rendered only when
            set, never guessed. */}
        <section id="apply" aria-labelledby="apply-heading" className="scroll-mt-20">
          <h2
            id="apply-heading"
            className="mt-14 text-xl font-semibold tracking-tight text-oxblood"
          >
            Apply as a food business
          </h2>
          <p className="mt-4 text-base leading-relaxed text-slate-600">
            J&apos;Toye runs an assisted pilot for food businesses.{" "}
            {POSITIONING_LINE} {OPERATOR_APPLICATION_REVIEW_LINE}
          </p>
          <ol className="mt-4 list-decimal space-y-2 pl-6 text-base leading-relaxed text-slate-600">
            <li>Tell us your business name, where you trade and what you sell.</li>
            <li>A person replies to agree a plan and your setup.</li>
            <li>
              We set up your storefront with you; you run a real service and we
              review it together.
            </li>
          </ol>
          {contact.anyRoute ? (
            <p className="mt-5 text-base leading-relaxed text-slate-600">
              {contact.emailHref ? (
                <>
                  <a
                    href={`${contact.emailHref}?subject=${encodeURIComponent(
                      "J'Toye pilot application"
                    )}`}
                    className={LEGAL_PROSE_LINK_CLASS}
                  >
                    Email us your application
                  </a>
                  {contact.phoneHref ? " or " : "."}
                </>
              ) : null}
              {contact.phoneHref ? (
                <>
                  <a href={contact.phoneHref} className={LEGAL_PROSE_LINK_CLASS}>
                    {contact.emailHref ? "call us" : "Call us"}
                  </a>
                  .
                </>
              ) : null}
            </p>
          ) : (
            <p className="mt-5 text-base leading-relaxed text-slate-600">
              J&apos;Toye has not yet published an email address or phone number
              for applications.
            </p>
          )}
          <p className="mt-5 text-base leading-relaxed text-slate-600">
            See the{" "}
            <Link href="/for-operators" className={LEGAL_PROSE_LINK_CLASS}>
              operator page
            </Link>
            , the{" "}
            <Link href="/pricing" className={LEGAL_PROSE_LINK_CLASS}>
              pricing
            </Link>{" "}
            and the{" "}
            <Link href="/legal/merchant-terms" className={LEGAL_PROSE_LINK_CLASS}>
              merchant terms (draft)
            </Link>
            . Already approved?{" "}
            <Link href="/auth/signin" className={LEGAL_PROSE_LINK_CLASS}>
              Sign in to your dashboard
            </Link>
            .
          </p>
        </section>

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
