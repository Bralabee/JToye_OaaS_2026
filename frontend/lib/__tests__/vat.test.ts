/**
 * COR-6 (QA-council 20260902-134741) — the client VAT preview must give the SAME answer as
 * `VatCalculator`, including on the baskets the seeded catalogue cannot produce.
 *
 * The plan's falsifiability warning is the reason several of these arms exist: all 22 seeded
 * products are STANDARD, so any assertion exercised only against live data passes by coincidence.
 * The ZERO, REDUCED, EXEMPT and mixed arms below are the arming step.
 */
import { asVatRate, predominantRate, vatFromGross, vatRateLabel } from "@/lib/vat"

describe("COR-6: vatFromGross mirrors VatCalculator.vatFromGross", () => {
  it("extracts the VAT already contained within a gross amount at the standard rate", () => {
    // 1200 * 20 / 120 = 200 exactly.
    expect(vatFromGross(1200, "STANDARD")).toBe(200)
    // 899 * 20 / 120 = 149.83 -> truncated to 149 (HMRC Notice 700 s17.5.1 permits round-down).
    expect(vatFromGross(899, "STANDARD")).toBe(149)
  })

  it("uses the 5% fraction for REDUCED, not the 20% one", () => {
    // 1050 * 5 / 105 = 50.
    expect(vatFromGross(1050, "REDUCED")).toBe(50)
  })

  it("returns 0 for ZERO and EXEMPT — this is the case the hardcoded 20% got wrong", () => {
    expect(vatFromGross(1200, "ZERO")).toBe(0)
    expect(vatFromGross(1200, "EXEMPT")).toBe(0)
  })

  it("truncates TOWARD ZERO on a negative gross, as Java long and Postgres both do", () => {
    // Math.floor(-1200*20/120) is -200 here, but -100 is the discriminating case:
    // trunc(-16.66) = -16, floor(-16.66) = -17. The server truncates.
    expect(vatFromGross(-100, "STANDARD")).toBe(-16)
    expect(vatFromGross(-1200, "STANDARD")).toBe(-200)
  })
})

describe("COR-6: predominantRate mirrors VatCalculator.predominantRate", () => {
  it("returns STANDARD for an empty basket", () => {
    expect(predominantRate([])).toBe("STANDARD")
  })

  it("keeps an all-ZERO basket ZERO — a genuinely zero-rated basket is NOT upgraded", () => {
    expect(
      predominantRate([
        { grossPennies: 500, rate: "ZERO" },
        { grossPennies: 700, rate: "ZERO" },
      ]),
    ).toBe("ZERO")
  })

  it("picks the rate carrying the greatest NET value, not the most lines", () => {
    // One big standard line outweighs two small zero lines.
    expect(
      predominantRate([
        { grossPennies: 5000, rate: "STANDARD" },
        { grossPennies: 200, rate: "ZERO" },
        { grossPennies: 200, rate: "ZERO" },
      ]),
    ).toBe("STANDARD")
    // ...and the reverse holds, which is what makes the previous assertion non-vacuous.
    expect(
      predominantRate([
        { grossPennies: 100, rate: "STANDARD" },
        { grossPennies: 5000, rate: "ZERO" },
      ]),
    ).toBe("ZERO")
  })

  it("breaks a tie toward STANDARD — the conservative direction for HMRC", () => {
    // Equal NET: a ZERO line's net is its gross; a STANDARD line's net is gross - vat.
    // 1200 STANDARD -> net 1000; 1000 ZERO -> net 1000. Strict '>' with STANDARD first wins.
    expect(
      predominantRate([
        { grossPennies: 1200, rate: "STANDARD" },
        { grossPennies: 1000, rate: "ZERO" },
      ]),
    ).toBe("STANDARD")
  })

  it("resolves REDUCED when it dominates", () => {
    expect(
      predominantRate([
        { grossPennies: 10_000, rate: "REDUCED" },
        { grossPennies: 120, rate: "STANDARD" },
      ]),
    ).toBe("REDUCED")
  })
})

describe("COR-6: asVatRate never silently zero-rates", () => {
  it("passes the four known rates through", () => {
    expect(asVatRate("STANDARD")).toBe("STANDARD")
    expect(asVatRate("REDUCED")).toBe("REDUCED")
    expect(asVatRate("ZERO")).toBe("ZERO")
    expect(asVatRate("EXEMPT")).toBe("EXEMPT")
  })

  it("falls back to STANDARD for an unknown, null or absent rate", () => {
    // Mirrors VatCalculator.predominantRate's null-line handling: no silent zero-rating. Being
    // wrong toward MORE tax is the safe error for a preview; the reverse would show a VAT-free
    // basket that is then taxed.
    expect(asVatRate(null)).toBe("STANDARD")
    expect(asVatRate(undefined)).toBe("STANDARD")
    expect(asVatRate("TELEPORT")).toBe("STANDARD")
  })
})

describe("COR-6: the label follows the resolved rate", () => {
  it("labels each rate distinctly — EXEMPT is not spelled '0%'", () => {
    expect(vatRateLabel("STANDARD")).toBe("incl. 20%")
    expect(vatRateLabel("REDUCED")).toBe("incl. 5%")
    expect(vatRateLabel("ZERO")).toBe("zero-rated")
    expect(vatRateLabel("EXEMPT")).toBe("exempt")
    // "zero-rated" and "exempt" are different VAT statuses and must not render identically.
    expect(vatRateLabel("ZERO")).not.toBe(vatRateLabel("EXEMPT"))
  })
})
