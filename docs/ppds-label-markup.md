# PPDS Allergen Label — Ingredient Markup Convention

**Audience:** Vendors and integrators creating or updating products via the API.

J'Toye generates **PPDS (Prepacked for Direct Sale) allergen labels** to meet the
UK **Natasha's Law** requirements (Food Information Regulations 2014 as amended,
in force 1 October 2021). A compliant PPDS label must show:

1. The **name of the food**.
2. A **full ingredients list** with the 14 regulated allergens **emphasised
   _within_ the list** — not in a separate "contains" statement.
3. A **durability date** — "Use by" or "Best before".
4. The **food business name and address**.

This document explains how to supply that data.

## Marking allergens in the ingredients text

Wrap each allergen word in **double asterisks** (`**...**`) inside
`ingredientsText`. The label renderer emboldens those words **inline**, exactly
where they appear in the list.

```
Wheat flour, **milk**, sugar, **egg**
```

renders as an ingredients list where **milk** and **egg** are bold, inline —
no separate "CONTAINS" block.

### Why `**` (double asterisk)

`**` never collides with real ingredient punctuation. Parentheses, commas, and
percentages are common in ingredient lists ("Yam (100%)", "Milk, Sugar") and are
always preserved literally. A single `*` is **not** a delimiter and is kept as-is.

### Markup rules (fail-soft)

The parser never rejects your text — if the markup is malformed it degrades
gracefully rather than erroring:

| Input | Result |
|-------|--------|
| `Wheat flour, **milk**, sugar` | "milk" emphasised inline |
| `**milk**, sugar, **egg**` | "milk" and "egg" emphasised |
| `**milk****egg**` | "milk" and "egg" emphasised, back to back |
| `Yam (100%)` | no emphasis; punctuation preserved |
| `Milk ** and egg` (dangling `**`) | stray `**` kept literal, no emphasis |
| `****` (empty pair) | delimiters removed, no emphasis |
| `Butter* (50%)` (single `*`) | `*` kept literal, no emphasis |

The parser pairs delimiters **left to right, non-nested**: each `**` opens an
emphasised run and the next `**` closes it.

## Durability date

Supply **both** of these on the product so the label can compute the durability
line at print time:

- `shelfLifeDays` — an integer number of days (e.g. `3`).
- `durabilityType` — `USE_BY` or `BEST_BEFORE`.

The label prints `Use by: <generation date + shelfLifeDays>` (or
`Best before: …`), formatted like `8 Jul 2026`.

## Business identity

The label prints the **name and address** of the shop that owns the product
(`shopId`). The shop must have a non-blank `address`.

## What makes a label 422 (Unprocessable Entity)

A compliant PPDS label **requires** all of the above. The label endpoint
(`GET /api/v1/products/{id}/label`) returns **HTTP 422** — naming every missing
field — instead of emitting a misleading, non-compliant PDF, when any of these
is absent:

- the owning shop (a `null` `shopId`, or a `shopId` that does not resolve to a
  shop your tenant owns), or its **address**;
- `shelfLifeDays`;
- `durabilityType`.

This is intentional: an incomplete PPDS label is a food-safety and regulatory
liability, so generation fails loudly until the data is supplied.

## Frontend "mark allergens" editor — fast-follow

A WYSIWYG "mark allergens" editor in the product form (so vendors can highlight
allergens without typing `**`) is a **documented fast-follow** UX enhancement.
The compliance-critical path (parser + storage + render + fail-loud) is fully
backend and complete today; vendors use the `**...**` convention above until the
editor ships.
