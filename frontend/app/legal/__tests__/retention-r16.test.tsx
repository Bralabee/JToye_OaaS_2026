/**
 * Phase 37-30 — retention row R-16 (37-28's client-IP digest, D-27; RWO-006).
 *
 * 37-28 gave the pseudonymous connection identifier on a cash order a declared lifetime —
 * nulled at COMPLETED / CANCELLED / REFUNDED, erased on request, and swept daily once older
 * than `jtoye.storefront.client-ip-digest.retention-hours` (72) by ClientIpDigestRetentionSweep.
 * A period the system enforces must be PUBLISHED, and a published period must name its
 * enforcement site so scripts/check-retention-enforcement.sh can hold the 72 to the code.
 *
 * The field-by-field transcription test (components/legal/__tests__/retention-table.a11y.test.tsx)
 * holds the page equal to the manifest; this file holds the manifest's R-16 to the 37-28 facts.
 */
import manifest from "../../../../docs/retention-manifest.json"
import { RETENTION_ROWS } from "@/app/legal/retention/page"

type Row = (typeof manifest.rows)[number]

function manifestRow(id: string): Row | undefined {
  return manifest.rows.find((r) => r.id === id)
}

describe("R-16: the connection pseudonym on a cash order", () => {
  it("is in the manifest as an Automated 72-hour period enforced at the 37-28 key and consumer", () => {
    const row = manifestRow("R-16")
    expect(row).toBeDefined()
    expect(row?.enforcement).toBe("Automated")
    expect(row?.period_value).toBe(72)
    expect(row?.period_unit).toBe("hours")
    expect(row?.claim_key).toBe("client_ip_digest_hours")
    expect((manifest as unknown as Record<string, unknown>).client_ip_digest_hours).toBe(72)

    const site = row?.enforced_by as
      | { path: string; key: string; kind: string; unit: string; consumer: { path: string; must_contain: string } }
      | null
      | undefined
    expect(site?.path).toBe("core-java/src/main/resources/application.yml")
    expect(site?.key).toBe("retention-hours")
    expect(site?.kind).toBe("yaml-env-default")
    expect(site?.unit).toBe("hours")
    expect(site?.consumer.path).toBe(
      "core-java/src/main/java/uk/jtoye/core/storefront/ClientIpDigestRetentionSweep.java"
    )
    expect(site?.consumer.must_contain).toBe("jtoye.storefront.client-ip-digest.retention-hours")
  })

  it("is published on /legal/retention with the 72 hours, the earlier ends, and the pseudonym made plain", () => {
    const published = RETENTION_ROWS.find((r) => r.id === "R-16")
    expect(published).toBeDefined()
    expect(published?.enforcement).toBe("Automated")
    expect(published?.period).toMatch(/\b72 hours\b/)
    // The customer-facing wording: a keyed pseudonym of the connection, never the address itself,
    // gone when the order completes or is cancelled, and erased on request (D-27).
    const text = `${published?.category} ${published?.detail} ${published?.period}`.toLowerCase()
    expect(text).toMatch(/connection/)
    expect(text).toMatch(/never the address|not the address|rather than the address/)
    expect(text).toMatch(/completed/)
    expect(text).toMatch(/cancelled/)
    expect(text).toMatch(/on request|ask us to erase/)
  })
})
