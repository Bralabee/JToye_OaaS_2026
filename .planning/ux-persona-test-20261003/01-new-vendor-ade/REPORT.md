# Persona 01: new vendor "Ade" (saved by the coordinator from the agent's returned report)

> The harness refused the subagent's own Write, so the coordinator saved this from its returned report. Screenshots 01–37, scripts `s1`–`s29`, `lib.mjs`, `api.mjs` and the test images are in this folder.
> **Coordinator root cause for ADE-02 (checked in the code):** `frontend/components/ui/image-uploader.tsx:398-399` picks `product.imageUrl || product.logoUrl || product.bannerUrl` from the legacy synchronous upload response. The banner endpoint returns the whole ShopDto, so when the shop already has a logo, `logoUrl` wins. `onUploadComplete` then writes the LOGO URL into the form's `bannerUrl`, and "Update Shop" persists it. The uploader needs to be told which field it is uploading.

**Verdict:** Ade could not go from zero to live by any route. There is no self-serve vendor signup and no visible way to contact J'Toye. He wouldn't pay yet and would tell a friend to wait.
**What would change his mind:** a real "Sign up as a vendor" button, or at least a WhatsApp number, that gets him to a shop he can preview the same day.

**Scores:**
- **Ease 3**
- **Trust 3**
- **Speed 6:** public pages quick when throttled, but photo processing never clears.
- **Mobile 6:** storefront excellent; dashboard tables cramped.
- **Overall 3**

## Persona
Adebayo "Ade", 41. Runs a Nigerian street-food stall at a south-London market. Uses WhatsApp Business, Instagram and SumUp, and has no patience for jargon. Started on a Pixel 7 (throttled to 150 ms, about 1.6 Mbps), then moved to a 1280x800 laptop.

**Environment note (not a finding):** between about 20:47Z and 20:53Z, core-java was on :9091 (#671). Every dashboard step was re-run against :9090 afterwards.

## Journey
1. **Landing on the phone** (`01`, `02`):
   - First text in about 0.8 s, page loaded in about 2.5 s.
   - "Order from local kitchens. Or run yours" and "go live in a day" spoke to him straight away.
   - The vendor card is below the fold, and the semi-transparent cookie banner overlaps text.
2. **/for-operators** (`03`):
   - Jargon: "assisted pilot", "service rail", "validation tracks".
   - £39/month and setup "From £99" are shown, next to a "PRICING TEST".
   - No contact at all; "return this pack to the person who shared it". The only email anywhere is privacy@ on /legal.
3. **/business-model-guide and /competitive** (`04`, `05`), both public and linked from the footer, read as internal strategy: "Do not claim: … self-service tenancy", "RAISED £0", "no SaaS subscription billing built". That contradicts "go live in a day", and trust fell sharply.
4. **"Check your pilot fit"** (`06`): local only ("Nothing is sent anywhere"), and leads nowhere.
5. **"Start your application"** (`07`–`09`): goes through /auth/signin to a Keycloak "Sign in to your account" page with no register, forgot-password or back link. The customer sign-in (`10`) does have "Create an account". **There is no self-serve vendor signup.** Ade gives up here, about 4 minutes in.
6. **Continuing as `admin-user`** (`13`): logged in in about 3.7 s; "Your onboarding is in progress".
7. **Go live, i.e. onboarding** (`14c`, `16`, `27`):
   - Status "In review": Allergen Passed; Business verification N/A (sole trader); Food hygiene "Manual review — No FSA establishment matched", 26–27 days old.
   - The page never says which shop is in review. Approvals shows it is Mama Ade's, which is already Published and Open.
   - The copy contradicts itself: "no separate J'Toye reviewer: an administrator on your own account resolves them" alongside "A reviewer is looking at this now".
   - There is no field to enter an FHRS ID.
   - As non-admin `tenant-a-user`, he also sees Finance, Approvals, Staff and Webhooks. Finance fails silently with a 403.
8. **Create a shop** (`17`–`19`):
   - The form is clear, and the postcode is geocoded automatically.
   - Opening hours are free text, and there is no collection-only option.
   - "Publish to storefront" is silently ignored: the shop stays Draft.
9. **Logo and banner** (`21`):
   - "Update Shop" sends `bannerUrl` = logoUrl, so the banner is replaced by the logo (reproduced twice, s14 and s16).
   - Every update changes the slug even with the name unchanged: a823cee3 → 7cd132a5 → 85a6253f.
10. **Products** (`22`, `23`):
    - SKU is required.
    - The allergen checkboxes are clear.
    - "butter (MILK)" in the ingredients with no allergen ticked saves as "No allergens", with no warning.
11. **Photos** (`24`–`26`):
    - Accepted with 202; the server has the image ACTIVE within about 3–6 s.
    - The dialog stays on "Processing…" for 30 s, 30 s and over 120 s; only reopening it shows the image.
    - A 4000x3000 photo was converted to WebP fine.
    - Only one image slot.
12. **Mobile dashboard** (`29`–`31`): no page overflow and a bottom tab bar, but the product table scrolls sideways.
13. **Storefront preview** (`28`):
    - A draft shop shows "Shop not found" (HTTP 200) even to the signed-in vendor.
    - Onboarding can't be started for the new shop, because the tenant's one application is used.
    - Mama Ade's on the phone (`36`, `37`): h1 in about 0.9 s, page loaded in about 7.8 s, 10/10 images. Looks good.
14. **Clean-up** (`32`–`35`):
    - Deleting a product with a photo fails with 409 "still referenced by an existing order". That is false: the blocking constraint is `product_media_product_id_fkey`, and neither product was ever ordered. The UI only shows "Request failed with status code 409".
    - Deleting the shop succeeds (204) but orphans its products (listed, yet GET returns 404 "Shop not found"). Editing an orphan silently moves it to "All Shops".

## Expectations vs reality
| Expected | Got | Felt |
|---|---|---|
| Live in 15 minutes, like Square or Instagram Shop | No signup; dead-end login; seed onboarding stuck 27 days | Shut out |
| Price before bank details | £39 + £99 shown, next to "pricing test" and "not production settlement" | Half-reassured |
| Hygiene rating from my FSA listing | Name and address lookup failed; can't enter an FHRS ID; his own admin "resolves" it | Clever, but he can't fix it |
| Upload a photo, see it | Endless "Processing…" | Looks broken |
| Banner on my shop | Replaced by the logo on save | Unnoticed until a customer sees it |
| Preview before going live | "Shop not found" | Flying blind |
| Delete a test item | 409 claiming a false order reference | "The system is lying" |

## Findings
| ID | Severity | Status | Finding | Evidence |
|---|---|---|---|---|
| ADE-01 | **blocker** | CONFIRMED | No self-serve vendor signup and no contact route; "Start your application" ends on a login with no register link | `07`–`10`; s3, s4 |
| ADE-02 | major | CONFIRMED x2 | "Update Shop" overwrites `bannerUrl` with the logo URL | s14, s16; `21` |
| ADE-03 | major | CONFIRMED (3 slugs) | Every shop update regenerates the public slug, so shared links change (old-link 404 after publishing is SUSPECTED) | s12, s14, s16 |
| ADE-04 | major | CONFIRMED x3 | Can't delete a product that has a photo: 409 with a false "referenced by an existing order" (`product_media_product_id_fkey`); raw error in the UI | `33`, `35`; s23, s24, s26 |
| ADE-05 | major | CONFIRMED | Shop delete orphans its products; editing an orphan moves it to "All Shops" | s25, s27, s28 |
| ADE-06 | major | CONFIRMED x3 | Image dialog stuck on "Processing…" although the server is ACTIVE within about 6 s; no re-check | `24`, `26`; s19, s20 |
| ADE-07 | major | CONFIRMED | Public footer pages expose internal strategy and contradict the landing page | `04`, `05` |
| ADE-08 | major | CONFIRMED | Onboarding copy contradicts itself; the vendor self-approves the FSA check; the shop under review isn't named | `14c`, `27` |
| ADE-09 | major | CONFIRMED | Ingredients naming MILK with no allergen ticked save as "No allergens", with no vendor warning | s18, s27 |
| ADE-10 | major | CONFIRMED | No draft preview; a second shop can never be onboarded or seen | `28`, `14c` |
| ADE-11 | minor | CONFIRMED | "Publish to storefront" checkbox silently ignored | `18`, `19` |
| ADE-12 | minor | CONFIRMED | SKU required (jargon for a stall holder) | `23` |
| ADE-13 | minor | CONFIRMED | Non-admin sees Finance, Approvals, Staff and Webhooks; Finance fails silently with 403 | s10, `15` |
| ADE-14 | minor | SUSPECTED (seed) | Mama Ade's is Published while its onboarding is still in manual review | `14c`, `36` |
| ADE-15 | polish | CONFIRMED | Cookie banner overlays text on mobile | `01` |
| ADE-16 | polish | CONFIRMED | No collection-only option; opening hours are free text | `17` |
| ADE-17 | polish | CONFIRMED | Unknown shop link returns a soft 404 (HTTP 200) | s21 |

## Goods to preserve
- The landing headline and food-first look.
- The price stated plainly.
- Automatic geocoding of the shop postcode.
- The 14 allergen checkboxes in plain English, and a label download per product.
- The photo pipeline (a 4000x3000 photo served as a light WebP).
- A lovely mobile storefront.
- Honest "what we don't do" boundaries.

## Verdict (in character)
"Bro, I wanted to be live by tonight. I couldn't even open an account — just a login box and no number to call. The photo kept saying 'processing', my banner vanished, and when I tried to delete a test dish it told me somebody had ordered it — nobody had. I wouldn't pay yet. If a friend asked, I'd say 'wait.'"

## Cleanup needed
Tenant-A products `c49a371c-c7f3-42a3-bb9e-5f901db98e3a` (ADE-SUYA) and `0254c57e-f3b1-4664-aa8f-04a88e73a244` (ADE-JOL), plus their media assets. Both are available=false and shopId=null, so they show on no storefront, but they can't be deleted (ADE-04). Shop `d46fe43f-…` is already deleted.
