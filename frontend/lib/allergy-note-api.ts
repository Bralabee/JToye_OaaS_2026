import apiClient from "@/lib/api-client"
import {
  ALLERGY_NOTE_READER_STAFF_COPY,
  ALLERGY_NOTE_READER_YOU_COPY,
} from "@/lib/allergen-copy"
import type { OrderDetail } from "@/types/api"

/**
 * The shop's acknowledgement of a customer's allergy note (31.1-22; #812, D-15).
 *
 * The server owns every rule (31.1-13): STAFF on the order's own shop, a typed 403 for another
 * shop, a typed 400 for an order with no note, and FIRST WRITE WINS — a repeat, by anyone,
 * returns the first acknowledgement unchanged. That makes the call idempotent by construction,
 * which is why the dashboard client's retry on a 5xx or a network error is safe here.
 *
 * Nothing in this module decides that a note was read. The caller shows "read" only from the
 * `allergyNoteAcknowledgedAt` the server returns (T-31.1-77).
 */
export async function acknowledgeAllergyNote(orderId: string): Promise<OrderDetail> {
  const res = await apiClient.post<OrderDetail>(
    `/api/v1/orders/${encodeURIComponent(orderId)}/allergy-note/acknowledgement`
  )
  return res.data
}

/**
 * The `sub` claim of the signed-in account's access token, or null.
 *
 * DISPLAY ONLY. It is used to say "you" when the viewer is the account that acknowledged the
 * note; it authorises nothing and the signature is not checked (the server checks it on every
 * request). The NextAuth session does not carry the subject on `session.user` at runtime (Auth.js
 * copies only name, email and picture), so the access token the client already holds is the one
 * place it is available.
 */
export function subjectOfAccessToken(token: string | null | undefined): string | null {
  if (!token) return null
  const parts = token.split(".")
  if (parts.length !== 3) return null
  try {
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/")
    const padded = b64 + "===".slice((b64.length + 3) % 4)
    const payload: unknown = JSON.parse(atob(padded))
    const sub = (payload as { sub?: unknown } | null)?.sub
    return typeof sub === "string" && sub.length > 0 ? sub : null
  } catch {
    return null
  }
}

const SUBJECT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * Who read the note, in words. 31.1-13 stores the authenticated principal (a Keycloak subject
 * UUID), and a STAFF user cannot read the staff directory (GROUP_ADMIN only), so no display name
 * is available to the kitchen. The rule:
 *   - the viewer's own account                → "you";
 *   - any other subject UUID                  → "a member of staff" (the raw id is never shown);
 *   - a non-UUID principal name (e.g. "kim")  → that name, as plain text;
 *   - nothing recorded                        → null (the caller says "Read at …").
 */
export function describeAllergyNoteReader(
  acknowledgedBy: string | null | undefined,
  viewerSubject: string | null
): string | null {
  if (!acknowledgedBy) return null
  if (viewerSubject && acknowledgedBy === viewerSubject) return ALLERGY_NOTE_READER_YOU_COPY
  if (SUBJECT_ID.test(acknowledgedBy)) return ALLERGY_NOTE_READER_STAFF_COPY
  return acknowledgedBy
}
