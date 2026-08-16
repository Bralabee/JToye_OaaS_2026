import type { Metadata } from "next"
import Link from "next/link"
import { PolicyPage, PolicySection } from "@/components/legal/policy-page"

export const metadata: Metadata = {
  title: "Cookie and browser-storage policy — J'Toye",
  description:
    "Every cookie and item of browser storage J'Toye uses, what it does, and how long it lasts.",
  alternates: { canonical: "/legal/cookies" },
}

/**
 * The exhaustive cookie and browser-storage disclosure (LGL-01).
 *
 * WHY IT IS NOT CALLED A COOKIE POLICY. Under PECR, storing or accessing
 * information on a user's terminal equipment is regulated by what it DOES, not
 * by which browser API does it. localStorage and sessionStorage are storage on
 * terminal equipment exactly as a cookie is. A document headed "cookies only"
 * would be inaccurate on its face here, because most of what this platform
 * stores in a browser is not a cookie at all — it is localStorage.
 *
 * THE INVENTORY BELOW WAS RE-DERIVED FROM SOURCE, not copied forward. The
 * completeness claim is the only claim this page makes, so a stale list is the
 * one defect that matters. It was measured across `app`, `components`, `lib`
 * and `hooks`, excluding tests, over every `localStorage.setItem`,
 * `sessionStorage.setItem` and cookie write, plus the identity library's own
 * defaults. Anything added later must be added here in the same change.
 *
 * TWO KEYS HOLD AN EMAIL ADDRESS. They are called out in their own rows AND in
 * prose, with their lifetimes, because an email address in browser storage is
 * personal data and burying it in a table row is the likeliest way for this
 * page to be technically complete and practically misleading.
 *
 * WHAT IS DELIBERATELY NOT LISTED. Two legacy keys are removed by
 * `clearMarker()` in `lib/customer-auth.ts` and are never written by any code
 * path — they exist only so that a browser carrying them from an older release
 * gets cleaned up. They are NOT disclosed here, and their names are deliberately
 * not repeated anywhere in this file: disclosing storage that does not exist is
 * a different error with the same cause as omitting storage that does, and a
 * reader cannot tell a "we store this" list from a "we clean this up" list.
 *
 * THE ACCESS COOKIE'S LIFETIME IS DESCRIBED, NEVER HARDCODED. It is set from
 * the token's own expiry, which is configured in the Keycloak realm and not in
 * this repository. Publishing the current number would make this page wrong the
 * first time the realm changes, and nothing here would report it.
 */

const SECTIONS = [
  "What this policy covers",
  "Cookies we set",
  "Information stored in your browser",
  "Information stored for the current tab only",
  "Third-party services",
  "What we do not use",
  "How to see and delete this information",
] as const

const LAST_UPDATED = "16 August 2026"
const VERSION = "1.0"

const LINK =
  "font-semibold text-amber-700 underline underline-offset-2 hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"

const REGION = "overflow-x-auto"
const TABLE = "w-full border-collapse text-sm leading-snug"
const CAPTION = "mb-3 text-left text-sm font-semibold text-oxblood"
const TH_COL =
  "border-b border-oxblood/20 px-3 py-2 text-left align-bottom text-sm font-semibold text-oxblood"
const TH_ROW =
  "border-b border-oxblood/10 px-3 py-2 text-left align-top text-sm font-semibold text-slate-700"
const TD = "border-b border-oxblood/10 px-3 py-2 align-top text-slate-700"

/**
 * One disclosure row. `name` is the literal key or cookie name; where the name
 * varies it is shown as a pattern with the variable part in angle brackets, so
 * a reader can match what they find in their own browser.
 */
interface StorageRow {
  name: string
  purpose: string
  lifetime: string
}

const COOKIE_ROWS: readonly StorageRow[] = [
  {
    name: "jtoye-customer-access",
    purpose:
      "Keeps you signed in to a shop while you browse and order. It holds the token that proves who you are to our servers.",
    lifetime:
      "The length of a sign-in session, set by our identity provider rather than by this site. It is short, and is renewed in the background.",
  },
  {
    name: "jtoye-customer-refresh",
    purpose:
      "Renews the cookie above so you are not signed out mid-order. Without it you would have to sign in again every few minutes.",
    lifetime:
      "Up to 30 days, or sooner if our identity provider ends the session first. Removed when you sign out.",
  },
  {
    name: "jtoye-customer-id",
    purpose:
      "Holds the identity token issued when you signed in, so we can tell which customer account a request belongs to.",
    lifetime: "Up to 30 days. Removed when you sign out.",
  },
  {
    name: "authjs.* (vendor dashboard)",
    purpose:
      "A family of cookies that signs vendors and their staff in to the business dashboard. It covers the dashboard session itself plus short-lived values used only while signing in: authjs.session-token, authjs.callback-url, authjs.csrf-token, authjs.pkce.code_verifier, authjs.state, authjs.nonce and authjs.challenge. They are set only on the dashboard, never while you shop.",
    lifetime:
      "The dashboard session; the sign-in values are discarded as soon as sign-in completes. All are removed on sign-out.",
  },
] as const

const LOCAL_ROWS: readonly StorageRow[] = [
  {
    name: "jtoye-cart-<shop>",
    purpose:
      "Your basket for one shop. There is one of these per shop you have added something to.",
    lifetime:
      "Until you sign out or clear your browser storage. Signing out removes every shop's basket.",
  },
  {
    name: "jtoye-checkout-email-<shop>",
    purpose:
      "The email address you last used at that shop's checkout, so it can be filled in for you next time and so you can look up your order.",
    lifetime:
      "No expiry is set: it stays until you clear it or your browser storage is cleared. This one holds an email address.",
  },
  {
    name: "jtoye-customer-id",
    purpose:
      "An opaque identifier for the signed-in customer. It stamps your basket so that a second person signing in on the same device cannot inherit it. It is not your email address or your name.",
    lifetime: "Until you sign out.",
  },
  {
    name: "jtoye-customer-logged-in",
    purpose:
      "A yes/no marker so the page can show the right header immediately, without waiting for a request to the server.",
    lifetime: "Until you sign out.",
  },
  {
    name: "jtoye-customer-expires-at",
    purpose:
      "When the marker above stops being valid, so a stale sign-in state is not shown to you.",
    lifetime: "Until you sign out.",
  },
  {
    name: "jtoye-guest-orders",
    purpose:
      "The order numbers of your most recent orders placed without an account, so you can find them again on this device. Capped at the twenty most recent.",
    lifetime: "Until you clear your browser storage.",
  },
  {
    name: "jtoye-cookie-notice-ack",
    purpose:
      "Records that you have seen the notice about this page, so it is not shown to you on every visit.",
    lifetime: "Until you clear your browser storage.",
  },
  {
    name: "shopContext",
    purpose:
      "Which shop a vendor's dashboard is currently filtered to. Dashboard only.",
    lifetime: "Until changed or cleared.",
  },
  {
    name: "theme",
    purpose:
      "Whether you chose the light or dark appearance for the dashboard.",
    lifetime: "Until changed or cleared.",
  },
  {
    name: "kds-muted",
    purpose:
      "Whether the kitchen display's new-order sound is muted. Kitchen display only.",
    lifetime: "Until changed or cleared.",
  },
] as const

const SESSION_ROWS: readonly StorageRow[] = [
  {
    name: "jtoye-track-email",
    purpose:
      "Carries the email address you typed on one order-tracking page across to the next, so you do not type it twice. This one holds an email address.",
    lifetime: "Cleared when you close the tab.",
  },
  {
    name: "jtoye-auth-return",
    purpose:
      "The page you were on when you started signing in, so you are returned there afterwards rather than to the home page.",
    lifetime: "Cleared when you close the tab.",
  },
  {
    name: "jtoye-pkce-verifier",
    purpose:
      "A one-time secret that proves the sign-in finishing in this tab is the same one that started here. It is what stops an intercepted sign-in from being completed by somebody else.",
    lifetime:
      "Discarded as soon as sign-in completes, and in any case when you close the tab.",
  },
  {
    name: "jtoye-oauth-state",
    purpose:
      "A one-time value that protects the sign-in against a cross-site request forgery attack.",
    lifetime:
      "Discarded as soon as sign-in completes, and in any case when you close the tab.",
  },
  {
    name: "jtoye-oauth-nonce",
    purpose:
      "A one-time value that stops a previously issued sign-in response from being replayed.",
    lifetime:
      "Discarded as soon as sign-in completes, and in any case when you close the tab.",
  },
] as const

function StorageTable({
  caption,
  label,
  nameHeading,
  rows,
}: {
  caption: string
  label: string
  nameHeading: string
  rows: readonly StorageRow[]
}) {
  return (
    /*
      A real table, per the S2a markup contract: one <table>, a <caption>,
      scope="col" on the headers and scope="row" on the name cell. It is NOT
      restyled to display:block at narrow widths — that strips table semantics
      in several screen readers — and the data is NOT duplicated into a
      mobile-only list, which would put a second copy of a legally operative
      disclosure in the DOM. The scroll region is a safety net rather than the
      plan, and it carries tabindex="0" so that it stays reachable by keyboard
      if it ever does overflow.
    */
    <div role="region" aria-label={label} tabIndex={0} className={REGION}>
      <table className={TABLE}>
        <caption className={CAPTION}>{caption}</caption>
        <thead>
          <tr>
            <th scope="col" className={TH_COL}>
              {nameHeading}
            </th>
            <th scope="col" className={TH_COL}>
              What it does
            </th>
            <th scope="col" className={TH_COL}>
              How long it lasts
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.name}>
              <th scope="row" className={TH_ROW}>
                <code>{row.name}</code>
              </th>
              <td className={TD}>{row.purpose}</td>
              <td className={TD}>{row.lifetime}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function CookiePolicyPage() {
  return (
    <PolicyPage
      title="Cookie and browser-storage policy"
      lastUpdated={LAST_UPDATED}
      version={VERSION}
      sections={SECTIONS}
      intro="This page lists everything J'Toye stores in your browser — cookies and browser storage alike — what each item is for, and how long it lasts. It is meant to be complete rather than representative."
    >
      <PolicySection heading="What this policy covers">
        <p>
          Most sites publish a cookie policy. That would not be accurate here,
          because most of what this platform keeps in your browser is not a
          cookie. It is <span className="font-semibold">browser storage</span>:
          the same kind of information, kept by a different mechanism.
        </p>
        <p>
          The law that governs this — the Privacy and Electronic Communications
          Regulations — is about storing information on your device and reading
          it back, whichever mechanism does it. So this page covers all three
          that we use:
        </p>
        <ul className="list-disc space-y-2 pl-6">
          <li>
            <span className="font-semibold">Cookies</span>, sent to our servers
            with each request. Ours are all set as{" "}
            <span className="font-semibold">HttpOnly</span>, which means the
            page&apos;s own JavaScript cannot read them, and{" "}
            <span className="font-semibold">SameSite=Lax</span>, which stops them
            being sent from other sites. On the live site they are also marked{" "}
            <span className="font-semibold">Secure</span>, so they are only ever
            sent over an encrypted connection.
          </li>
          <li>
            <span className="font-semibold">Local storage</span>, kept in your
            browser and not sent anywhere automatically. It survives closing the
            tab.
          </li>
          <li>
            <span className="font-semibold">Session storage</span>, the same but
            scoped to one tab, and discarded when that tab closes.
          </li>
        </ul>
        <p>
          <span className="font-semibold">
            Two of the items below hold an email address
          </span>{" "}
          — one in local storage, one in session storage. Both are named in their
          tables, and both say so in their own row rather than leaving you to
          work it out.
        </p>
      </PolicySection>

      <PolicySection heading="Cookies we set">
        <p>
          These are set by J&apos;Toye. Every one exists to keep you signed in;
          none of them is used to track you or to build a profile.
        </p>
        <StorageTable
          caption="Cookies set by J'Toye, what each does and how long it lasts"
          label="Cookies set by J'Toye"
          nameHeading="Cookie"
          rows={COOKIE_ROWS}
        />
        <p>
          The first cookie&apos;s lifetime is set by our identity provider rather
          than by this site, so it is described here instead of being written down
          as a number. A number published in one place and configured in another
          becomes wrong the first time the configuration changes, and nothing
          would tell you it had.
        </p>
      </PolicySection>

      <PolicySection heading="Information stored in your browser">
        <p>
          These are kept in local storage. They stay until they are removed, so
          they survive closing the tab and restarting the browser.
        </p>
        <StorageTable
          caption="Local storage used by J'Toye, what each item does and how long it lasts"
          label="Local storage used by J'Toye"
          nameHeading="Name"
          rows={LOCAL_ROWS}
        />
        <p>
          Where a name ends in <code>&lt;shop&gt;</code>{" "}
          there is one item per shop, and the shop&apos;s short name is on the
          end — so a basket at a
          shop called <code>rosies</code> is stored as{" "}
          <code>jtoye-cart-rosies</code>.
        </p>
        <p>
          <span className="font-semibold">
            The checkout email item holds an email address and has no expiry set.
          </span>{" "}
          It remains in your browser until you clear it, which you can do at any
          time using the steps at the end of this page. We keep it because
          re-typing an email address on every order is the most common complaint
          about ordering as a guest; we are telling you plainly because it is
          personal data sitting on your device.
        </p>
      </PolicySection>

      <PolicySection heading="Information stored for the current tab only">
        <p>
          These are kept in session storage. Closing the tab discards them, and
          they are not shared with other tabs.
        </p>
        <StorageTable
          caption="Session storage used by J'Toye, what each item does and how long it lasts"
          label="Session storage used by J'Toye"
          nameHeading="Name"
          rows={SESSION_ROWS}
        />
        <p>
          <span className="font-semibold">
            The order-tracking item holds an email address, and it is cleared when
            you close the tab.
          </span>{" "}
          The last three are security values used during sign-in. They are
          one-time and short-lived, and they are what make the sign-in resistant
          to interception and replay.
        </p>
      </PolicySection>

      <PolicySection heading="Third-party services">
        <p>
          One third party runs code in your browser on this site, and only in one
          place.
        </p>
        <h3 className="text-base font-semibold text-oxblood">
          Stripe, on the payment step
        </h3>
        <p>
          When you reach the payment step of a checkout, we load Stripe&apos;s
          payment form from <code>js.stripe.com</code>{" "}
          so your card details go directly to Stripe and never pass through
          J&apos;Toye. Stripe sets its
          own cookies in your browser to process the payment and to detect fraud.
        </p>
        <p>
          Those cookies are Stripe&apos;s, not ours, and their names and lifetimes
          are Stripe&apos;s to publish rather than ours to guess — we do not list
          them here because we do not set them and cannot guarantee a list we
          copied would stay accurate. They are described in{" "}
          <a
            href="https://stripe.com/legal/cookies-policy"
            className={LINK}
            rel="noopener noreferrer"
            target="_blank"
          >
            Stripe&apos;s cookie policy
          </a>
          , and their handling of your card details is covered by{" "}
          <a
            href="https://stripe.com/privacy"
            className={LINK}
            rel="noopener noreferrer"
            target="_blank"
          >
            Stripe&apos;s privacy policy
          </a>
          .
        </p>
        <p>
          Stripe&apos;s code is not loaded while you browse shops, add to a
          basket, or use any other part of the site. It loads on the payment step
          and nowhere else.
        </p>
      </PolicySection>

      <PolicySection heading="What we do not use">
        <p>Stated plainly, because the absence is the point:</p>
        <ul className="list-disc space-y-2 pl-6">
          <li>
            <span className="font-semibold">No advertising cookies.</span> We do
            not advertise to you here or anywhere else based on what you do on
            this site.
          </li>
          <li>
            <span className="font-semibold">
              No analytics or tracking scripts.
            </span>{" "}
            There is no Google Analytics, no tag manager, and no product-analytics
            tool of any kind on this site.
          </li>
          <li>
            <span className="font-semibold">No social media pixels</span> and no
            cross-site tracking.
          </li>
          <li>
            <span className="font-semibold">No profiling and no data selling.</span>{" "}
            Nothing in the tables above is used to build a picture of you, and
            none of it is shared with anyone for their own purposes.
          </li>
        </ul>
        <p>
          That is why this site does not put an accept-or-reject choice in front
          of you. Everything listed above is either needed to provide the service
          you asked for — signing you in, keeping your basket, taking your payment
          — or a preference you set yourself, such as the appearance of the
          dashboard. There is no advertising or analytics storage to decline,
          because there is none.
        </p>
        <p>
          If that ever changes, this page and the version number at the top of it
          change with it, and we would ask before setting anything that needed
          your agreement.
        </p>
      </PolicySection>

      <PolicySection heading="How to see and delete this information">
        <p>
          Everything above is stored on your own device and you can inspect or
          remove all of it yourself.
        </p>
        <ul className="list-disc space-y-2 pl-6">
          <li>
            <span className="font-semibold">Signing out</span> removes the
            sign-in cookies and every stored basket.
          </li>
          <li>
            <span className="font-semibold">Closing the tab</span> discards
            everything in the session-storage table, including the order-tracking
            email address.
          </li>
          <li>
            <span className="font-semibold">Clearing site data</span>{" "}
            in your browser&apos;s settings removes everything on this page,
            including the
            checkout email address. In most browsers this is under privacy
            settings, as &quot;cookies and site data&quot;. You can also delete
            individual items from the storage section of your browser&apos;s
            developer tools.
          </li>
          <li>
            <span className="font-semibold">Blocking cookies</span> for this site
            is your choice, but the sign-in cookies are what keep you signed in —
            blocking them means you will not be able to sign in or check out.
          </li>
        </ul>
        <p>
          For what happens to information held on our servers rather than in your
          browser — and for your rights over it — see our{" "}
          <Link href="/legal/privacy" className={LINK}>
            privacy notice
          </Link>{" "}
          and our{" "}
          <Link href="/legal/retention" className={LINK}>
            data retention schedule
          </Link>
          .
        </p>
      </PolicySection>
    </PolicyPage>
  )
}
