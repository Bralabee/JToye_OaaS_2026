import type { Metadata } from "next"
import Link from "next/link"
import { getCompanyInfo, resolveControllerContact } from "@/lib/company"
import { PolicyPage, PolicySection } from "@/components/legal/policy-page"

export const metadata: Metadata = {
  title: "Privacy notice — J'Toye",
  description:
    "How J'Toye and the vendors on it use your personal data, who is responsible for what, and how to exercise your rights.",
  alternates: { canonical: "/legal/privacy" },
}

/**
 * The platform privacy notice (LGL-01, decisions D-14 and D-15).
 *
 * WHY THIS PAGE EXISTS AT ALL. `docs/legal/article-9-allergen-basis.md` has
 * carried "Write the privacy notice — there is currently none" as its own
 * recommended step 4 since 2026-07-30. A UK consumer-facing storefront is
 * required to have one, and until this page existed `/legal` carried a Companies
 * House trading disclosure and nothing else.
 *
 * THREE THINGS HERE ARE EASY TO GET WRONG, so each is called out where it
 * happens rather than left to a reviewer to notice:
 *
 *   1. THE ESSENCE IS REPRODUCED, NOT PARAPHRASED. Article 26(2) requires the
 *      essence of the joint-controller arrangement to be made available to the
 *      data subject, and this page is where that happens. The text below is
 *      copied from the delimited essence block in
 *      `docs/legal/article-26-arrangement.md`. A paraphrase would be a second,
 *      differently worded arrangement, and the two would drift. The only
 *      substitution made is the identity, which is interpolated from
 *      getCompanyInfo() instead of hardcoded — the rendered words are the
 *      arrangement's words.
 *
 *   2. TWO LINES, BOTH TRUE. `/legal` says vendors own their own trading
 *      disclosures. This page says J'Toye and the vendor are joint controllers
 *      for consumer order data. Those answer different statutory questions and
 *      do not conflict — but a regulator reads both pages, so the reconciliation
 *      is stated explicitly in its own section rather than left to the reader.
 *
 *   3. RETENTION PERIODS LIVE IN EXACTLY ONE PLACE. This notice links to
 *      /legal/retention and states no period of its own. Two copies of a number
 *      is precisely how the retention gate's whole point gets defeated: the
 *      copies drift, and nothing reports that they have.
 *
 * IDENTITY COMES FROM getCompanyInfo(). The name and number are never hardcoded
 * here. The registered name is not unique on the Companies House register — a
 * dissolved company of a closely similar name exists and this platform's own
 * marketing site has already cited the wrong one once — so the number is the
 * disambiguator and it arrives at render time from one source.
 */

const SECTIONS = [
  "Who we are",
  "Who is responsible for what",
  "The trading line and the GDPR line",
  "What we collect and why",
  "Allergen and dietary information",
  "How long we keep it",
  "Your rights, and how to exercise them",
  "Complaints",
  "Changes to this notice",
] as const

const LAST_UPDATED = "16 August 2026"
const VERSION = "1.0"

const LINK =
  "font-semibold text-amber-700 underline underline-offset-2 hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"

const SUB_HEADING = "text-base font-semibold text-oxblood"

export default function PrivacyNoticePage() {
  const company = getCompanyInfo()
  const contact = resolveControllerContact(company)

  return (
    <PolicyPage
      title="Privacy notice"
      lastUpdated={LAST_UPDATED}
      version={VERSION}
      sections={SECTIONS}
      intro="This notice covers ordering food through J'Toye. It explains what happens to your information, which decisions are ours and which belong to the shop you ordered from, and how to ask us to do something about it."
    >
      <PolicySection heading="Who we are">
        <p>
          The J&apos;Toye platform is operated by {company.legalName}, registered
          in {company.registrationJurisdiction} under company number{" "}
          {company.companyNumber}. Where you see a company number in any
          J&apos;Toye legal document, that is the one that identifies us.
        </p>
        {/*
          Guarded on the resolver, with the heading INSIDE the guard. A term
          rendered above an empty value is simultaneously a broken page and a UK
          GDPR Art. 13 failure, and it gets triaged as the first. The registered
          office is deliberately unconfigured at the time of writing, so this
          block genuinely exercises its own fallback in production rather than
          only in a test.
        */}
        {contact.anyRoute ? (
          <>
            <h3 className={SUB_HEADING}>How to contact us about your data</h3>
            {contact.postal ? (
              <p>
                <span className="font-semibold">Registered office:</span>{" "}
                {contact.postal}
              </p>
            ) : null}
            {contact.emailHref ? (
              <p>
                <span className="font-semibold">
                  Data protection contact:
                </span>{" "}
                <a href={contact.emailHref} className={LINK}>
                  {contact.email}
                </a>
              </p>
            ) : null}
          </>
        ) : null}
      </PolicySection>

      <PolicySection heading="Who is responsible for what">
        <p>
          This is one notice covering the whole platform. The particular shop you
          are buying from is named to you at the point of order, on the shop page
          and again on your order confirmation, so you always know which business
          is the other party.
        </p>
        <p>
          Below is the essence of the written arrangement between J&apos;Toye and
          the shops on it. We publish it because the law requires us to make it
          available to you.
        </p>
        {/*
          REPRODUCED FROM docs/legal/article-26-arrangement.md, between its
          ESSENCE:BEGIN and ESSENCE:END markers. Do not edit the wording here in
          isolation — edit the arrangement, then copy it across, or the published
          essence and the arrangement it is the essence OF will say different
          things.
        */}
        <div className="border-l-2 border-amber-700 pl-4 sm:pl-6">
          <h3 className={SUB_HEADING}>
            Who is responsible for your information when you order
          </h3>
          <p className="mt-4">
            When you order food through J&apos;Toye, two businesses are involved:{" "}
            <span className="font-semibold">{company.legalName}</span> (company
            number {company.companyNumber}), which runs the platform, and{" "}
            <span className="font-semibold">the shop you ordered from</span>,
            which makes and supplies your food. The shop is named to you when you
            order.
          </p>
          <p className="mt-4">
            For the information created by your order — your name, contact
            details, delivery address, what you ordered and what you paid —{" "}
            <span className="font-semibold">
              J&apos;Toye and the shop are jointly responsible
            </span>
            . J&apos;Toye decides how the platform collects and stores it; the
            shop decides what it needs in order to serve you.
          </p>
          <p className="mt-4">
            Some things are J&apos;Toye&apos;s responsibility alone. Your
            J&apos;Toye storefront account and password are ours, not the
            shop&apos;s. Records a shop keeps about you for its own reasons —
            including any note it makes of your allergies — are the shop&apos;s
            responsibility, and J&apos;Toye only stores them on the shop&apos;s
            behalf.{" "}
            <span className="font-semibold">
              J&apos;Toye does not check your order against any allergy
              information a shop has recorded, and does not hold allergy
              information about you.
            </span>{" "}
            What you are shown at checkout is what the shop has declared about the
            food in that order.
          </p>
          <p className="mt-4">
            <span className="font-semibold">
              You can contact J&apos;Toye about your information, once, for every
              shop you have ordered from.
            </span>{" "}
            You do not need to contact each shop separately. We will act on your
            request across every shop that holds your details. You can also
            contact any shop directly, and you can complain to the Information
            Commissioner&apos;s Office at any time.
          </p>
          <p className="mt-4">
            <span className="font-semibold">
              No J&apos;Toye employee can browse across shops to look at your
              details.
            </span>{" "}
            Requests are carried out by an automated process that works through
            one shop at a time. That is deliberate: it is how we can offer you a
            single place to ask while keeping each shop&apos;s records separate
            from every other shop&apos;s.
          </p>
          <p className="mt-4">
            The full arrangement between J&apos;Toye and the shops is a written
            document. This is its essence, which we publish because the law
            requires us to make it available to you.
          </p>
        </div>
      </PolicySection>

      <PolicySection heading="The trading line and the GDPR line">
        <p>
          Two different lines are drawn across this platform and both are true at
          the same time. They answer different questions, so they have different
          answers.
        </p>
        <p>
          On{" "}
          <Link href="/legal" className={LINK}>
            our legal and company information page
          </Link>{" "}
          we say that the shops listed on J&apos;Toye are run by their own
          businesses and remain responsible for their own trading disclosures.
          That is the <span className="font-semibold">trading</span> line: who
          sells you the food, whose terms of sale apply, and who is answerable for
          the meal itself. It is the shop.
        </p>
        <p>
          This notice draws the{" "}
          <span className="font-semibold">data protection</span> line, and for
          the information created by your order the answer is{" "}
          <span className="font-semibold">both of us, jointly</span>. A shop can
          be solely responsible for what it sells you and jointly responsible with
          us for the personal data created by selling it. Neither statement
          weakens the other, and neither page should be read as correcting the
          other.
        </p>
      </PolicySection>

      <PolicySection heading="What we collect and why">
        <p>
          We collect the following, and no more than we need for each purpose.
        </p>

        <h3 className={SUB_HEADING}>Order information</h3>
        <p>
          Your name, email address, phone number, delivery address where the
          order is delivered, any notes you add, what you ordered, what you paid
          and the status of the order. We need this to take and fulfil your
          order, so our lawful basis is performance of a contract with you.
        </p>

        <h3 className={SUB_HEADING}>Account information</h3>
        <p>
          If you create a J&apos;Toye storefront account, your sign-in identity
          and the order history we show back to you across shops. This is ours
          alone, on the basis of performance of a contract with you.
        </p>

        <h3 className={SUB_HEADING}>Payment information</h3>
        <p>
          We do not hold your card details. Card details are entered directly into
          Stripe&apos;s own payment form in your browser and never pass through
          J&apos;Toye. We keep the payment status and reference against your
          order so we know it was paid.
        </p>

        <h3 className={SUB_HEADING}>Information stored in your browser</h3>
        <p>
          Your basket, your signed-in state and a small number of other items are
          held in your own browser rather than on our servers. Every one of them
          is listed, with what it does and how long it lasts, in our{" "}
          <Link href="/legal/cookies" className={LINK}>
            cookie and browser-storage policy
          </Link>
          . Two of them hold an email address, and that policy says which.
        </p>

        <h3 className={SUB_HEADING}>Allergen notes a shop records about you</h3>
        <p>
          A shop can record allergy and dietary notes against its own customer
          records. That information belongs to the shop, not to us — see the next
          section, which sets out exactly what we do and do not do with it.
        </p>
      </PolicySection>

      <PolicySection heading="Allergen and dietary information">
        <p>
          This section is the one most likely to be misread, so it says four
          separate things plainly. They are recorded in full in our{" "}
          <span className="font-semibold">Article 9 determination</span> of 30
          July 2026, extended on 16 August 2026; this is a summary of that
          determination and not a fresh argument about it.
        </p>
        <p>
          <span className="font-semibold">
            One — the shop is responsible for it, not us.
          </span>{" "}
          Where a shop records allergy or dietary notes against your record, the
          shop decided to record them, for its own purpose of serving you safely.
          Allergy information is health information, and the shop is responsible
          for having a lawful reason to hold it. We store it on the shop&apos;s
          behalf and do not use it for our own purposes.
        </p>
        <p>
          <span className="font-semibold">
            Two — we do not check your order against it.
          </span>{" "}
          J&apos;Toye does not consult any stored allergy note at checkout or
          anywhere else in the ordering flow. We never learn your allergies, and
          nothing in the ordering flow compares you against a product. This is a
          decision we took and recorded, not an omission.
        </p>
        <p>
          <span className="font-semibold">
            Three — what you see at checkout is about the food, not about you.
          </span>{" "}
          The allergen set shown to you at checkout is assembled from what the
          shop has declared about the products in that order, and you are asked to
          confirm you have read it. It is product information. It is not derived
          from anything we hold about you, and it is not a confirmation that the
          order suits your own needs.
        </p>
        <p>
          <span className="font-semibold">
            Four — you can still ask us for it.
          </span>{" "}
          If a shop has recorded allergen notes against your record, that
          information is included in the copy of your data you receive if you make
          an access request. Not using a piece of information for our own purposes
          is a different thing from withholding it from the person it is about,
          and we do not withhold it.
        </p>
      </PolicySection>

      <PolicySection heading="How long we keep it">
        <p>
          Different categories of information are kept for different lengths of
          time, and some periods are set by law rather than by us.
        </p>
        <p>
          The periods are published in one place so there is a single answer
          rather than several that can drift apart. See our{" "}
          <Link href="/legal/retention" className={LINK}>
            data retention schedule
          </Link>
          , which lists each category, how long it is kept, the reason, and
          whether the period is enforced automatically or operationally. This
          notice deliberately does not restate those periods.
        </p>
      </PolicySection>

      <PolicySection heading="Your rights, and how to exercise them">
        <p>Under UK data protection law you have the right to:</p>
        <ul className="list-disc space-y-2 pl-6">
          <li>
            <span className="font-semibold">ask for a copy</span> of the personal
            data we hold about you;
          </li>
          <li>
            <span className="font-semibold">have it corrected</span> if it is
            wrong or incomplete;
          </li>
          <li>
            <span className="font-semibold">have it erased</span> in the
            circumstances the law allows;
          </li>
          <li>
            <span className="font-semibold">receive it in a portable form</span>,
            so you can take it elsewhere;
          </li>
          <li>
            <span className="font-semibold">object to</span>, or ask us to
            restrict, what we do with it.
          </li>
        </ul>
        <p>
          You may exercise these rights against J&apos;Toye or against the shop
          directly. You do not have to choose correctly: a request that reaches
          either of us is a valid request.
        </p>

        {/*
          The contact block is guarded on `anyRoute` with its heading inside the
          guard, per the resolver's contract. The fallback is NOT an empty
          mailto and NOT silence: it names the routes that genuinely exist
          regardless of configuration — the shop, and the regulator. A published
          route that resolves nowhere is worse than no route, because it looks
          discharged.
        */}
        {contact.anyRoute ? (
          <>
            <h3 className={SUB_HEADING}>How to make a request</h3>
            {contact.emailHref ? (
              <p>
                Write to{" "}
                <a href={contact.emailHref} className={LINK}>
                  {contact.email}
                </a>
                , which is monitored for data protection requests. Tell us what
                you want us to do; you do not need to name every shop you have
                ordered from.
              </p>
            ) : null}
            {contact.postal ? (
              <p>
                You can also write to us at {contact.postal}, marking your letter
                for the attention of data protection.
              </p>
            ) : null}
          </>
        ) : (
          <>
            <h3 className={SUB_HEADING}>How to make a request</h3>
            <p>
              We do not currently publish a data protection contact address on
              this site. Until we do, you can make a request to the shop you
              ordered from, which is named on your order confirmation, and you
              can complain to the Information Commissioner&apos;s Office using the
              route in the next section. Both of those routes are open to you now.
            </p>
          </>
        )}

        <h3 className={SUB_HEADING}>How we confirm it is you</h3>
        <p>
          Before we act on a request we confirm that you control the email address
          it concerns, by sending that address a single-use confirmation link. We
          do this because acting on an unverified erasure request would let one
          person delete another person&apos;s records. Until the link is
          followed, nothing is actioned.
        </p>
        <p>
          Once confirmed, an erasure request is carried out across every shop on
          the platform that holds your details, one shop at a time, by an
          automated process. We keep a record that the erasure happened — the
          record identifies you only by a one-way fingerprint of your email
          address, never the address itself — so that we can evidence it without
          keeping the very data you asked us to remove.
        </p>
        <p>
          The law gives us one month to respond to a request, and we will tell you
          if we need longer, which the law allows in limited circumstances.
        </p>
      </PolicySection>

      <PolicySection heading="Complaints">
        <p>
          If you are unhappy with how we have handled your information, please
          tell us first if you can — it is usually the quickest way to put
          something right.
        </p>
        <p>
          You have the right to complain to the Information Commissioner&apos;s
          Office, the UK supervisory authority for data protection, at any time
          and whether or not you have raised it with us. You can reach them at{" "}
          <a
            href="https://ico.org.uk/make-a-complaint/"
            className={LINK}
            rel="noopener noreferrer"
            target="_blank"
          >
            ico.org.uk/make-a-complaint
          </a>
          . Complaining to the ICO does not cost anything and does not affect any
          other right you have.
        </p>
      </PolicySection>

      <PolicySection heading="Changes to this notice">
        <p>
          This notice carries a version number and a last-updated date at the top
          of the page, and our{" "}
          <Link href="/legal/cookies" className={LINK}>
            cookie and browser-storage policy
          </Link>{" "}
          carries its own. Those are how you can tell whether what you are reading
          is what was in force when something happened to your data.
        </p>
        <p>
          If we change this notice in a way that materially affects you, we will
          raise the version number and say what changed, rather than editing the
          text silently. Small corrections that do not change its meaning may be
          made without a version change.
        </p>
      </PolicySection>
    </PolicyPage>
  )
}
