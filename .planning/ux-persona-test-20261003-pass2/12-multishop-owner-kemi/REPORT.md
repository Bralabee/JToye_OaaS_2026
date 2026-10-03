# Persona 12: Kemi, multi-site owner (pass 2)

> The coordinator saved this from the agent's returned summary and condensed report, because subagents are blocked from writing report files. Full detail is in findings.json.

**Kemi (multi-site owner, pass 2): I would not use, pay for or recommend this for three sites.** It works for one shop run by one person, but every multi-site job hits a dead end. Worse, the screens say things the data contradicts: "you're live", "scoped to this shop", "no access".

**REPORT.md was not written.** The harness refused to let me create a report file, so the narrative is condensed below for you to save. findings.json (22 entries, every evidence file checked to exist), the scripts and 74 screenshots are in `/home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/`.

**Scores:** ease 3, trust 2, speed 6 (about 1 s per click on localhost; no throttled test), mobile 5, overall 3.

**Top findings:**
1. **P2-KEM-01, blocker (seen twice).** Removing a manager's last shop access makes him admin of every shop. His token could read the other shop's orders and open staff management, and after a reload his kitchen showed the other site. Meanwhile the Staff page tells Kemi he has no access. Cause: strict scoping is off, so anyone with no access row is treated as a tenant-wide admin.
2. **P2-KEM-03, blocker (repeats pass-1 ADE-10).** The second and third shops can never go live: "An onboarding already exists for this tenant". The Go-live page still says "Your storefront is live" while all 4 shops are Draft and the onboarded shop's public page is a 404. New sites can only take orders staff type in.
3. **P2-KEM-02, major.** Giving Bayo one shop changes nothing, because everyone who signs in is automatically admin of all shops, and nothing warns her. Removing that admin row fails as "last group admin". The only way round is for Kemi to grant herself admin first.
4. **P2-KEM-05 / 06 / 08, major (finance).**
   - With one shop selected, the dashboard says "scoped to this shop" but shows the whole business's £48 (Lewisham's real figure is £16).
   - The Finance page and its API ignore the shop and date filters.
   - There is no export, no month-to-date and no cash/card split; cash orders record payment status NONE.
   - Managers get no figures at all, and their home page says "No financial data yet".
5. **P2-KEM-07, major.** A completed order can be deleted (204), and the ledger then keeps sales whose orders return 404. The code only requires shop-manager rights, so a manager could likely do it too; I only tested as admin.

**Other majors:**
- **No menu copy (P2-KEM-09).** CSV import ignores the shop she selected and creates items that appear under no shop filter. From the code, they also appear on no storefront.
- **Delivery fee (P2-KEM-10).** It can't be set, so orders charge £0 against "£2.99" in the shop text, and "collection only" can't be enforced.
- **Kitchen tickets (P2-KEM-12).** Every ticket shows "ORD-0000…", so they all look the same.
- **No invite (P2-KEM-14).** A new manager needs an account first and then becomes admin of everything on first sign-in.

**Minors:**
- The publish tick throws away the whole edit with raw API text.
- Holiday-closure text fails open (code reading only, so suspected).
- After a revoke, the kitchen keeps stale tickets and blames the connection.
- There is no VAT-rate field.
- The admin menu items show for scoped managers.
- The tablet orders table is clipped.

**Goods to keep:** once scoping is set up, more than 30 attempts with Bayo's token on the other shop were all refused (403). Removing one of several shops cuts access on the very next request. A dish from another site can't go on an order. The last-admin guard stops a lockout. Ledger VAT is correct.

**Clean-up:** all kemi-p2 grants revoked. Bayo is back to one auto-created admin row (same access as before, new row id). All kemi-p2 orders, products and both shops are deleted, plus pass 1's leftover `kemi-camberwell`. I could not remove 3 ledger rows (£48.00, £7.99 VAT, orders …85C353C4, …24C38E33, …AE561258): there is no delete for ledger entries. Between about 23:21 and 23:57 BST Bayo was scoped or briefly re-elevated, which concurrent personas using tenant-b-user may have noticed.

---

**REPORT.md content (condensed, in character)**

*Persona:* Kemi, 48, owns three takeaways (tenant B, admin-user-b); Bayo (tenant-b-user) runs one. 1440px laptop at home, iPhone 13 between sites. Run 2026-10-03, 23:05–23:58 BST.

*Journey:*
1. **Two new shops** (`02`, `03`). Quick to add, but the Publish tick was ignored. There is no delivery-fee field, no collection-only switch, and hours are a free-text weekly grid.
2. **Menus** (`07`, `08`, `s05`, `14`, `15`). Peckham got two dishes, Lewisham one. There is no duplicate or copy option. The CSV import with Lewisham selected created "All Shops" items that vanished from the Lewisham list, so she had to edit each one by hand.
3. **Going live** (`16`, `19`, `20`). Go-live says she's live when nothing is published. The API refuses a second onboarding. The publish tick errors with "POST /api/v1/onboarding/go-live (APPROVED -> LIVE)" and drops her hours change.
4. **Orders** (`21`, `22`). She took phone orders herself: 5 orders, 3 completed. Delivery was charged at £0. The collection-only site accepted a delivery order. Payment shows NONE.
5. **Per-site numbers** (`24-*`). Every "scoped" view shows £48, the whole business. No export. `/dashboard/payments` is a 404.
6. **Scoping Bayo** (`26`–`29`). It only worked after she granted herself admin first.
7. **As Bayo** (`31-*`, `30`). The API boundary held. He gets no finance figures, and the admin menu items are still visible to him.
8. **Revoking with his kitchen open** (`s13`, `s15`, `46`). Removing his only shop made him admin of everything. Removing one of two shops worked, but stale tickets stayed on screen.
9. **Tablet and phone** (`31-*`, `42`, `43`). No sideways page scroll. The tablet table is clipped, the phone opens on an explainer, and tickets are truncated.
10. **Invites.** None exist.

*Expected vs got:*

| Expected | Got | How it felt |
|---|---|---|
| Copy my menu to a new site | Retype it, or an import that hides the items | Tedious and risky for allergens |
| Publish sites 2 and 3 | Impossible, while the page says "live" | Cheated |
| Give Bayo one site | He was admin of everything until I found a workaround | Alarming |
| Take Bayo off a site | He became admin of everything while the screen said "no access" | Frightening |
| Per-site takings | Whole-business figure labelled as one site | Can't trust any number |
| Something for my brother's accountant | Nothing to export; cash/card not recorded | Back to spreadsheets |
| Delivery fee and collection-only per site | Free text only; £0 charged | Losing money per delivery |
| Christmas closure | Weekly grid only | Will get Christmas orders |

*Verdict:* "Two of my shops can't be found by customers. Taking a manager off a site gave him the keys to everything. And my nightly number is the whole business pretending to be one shop. What would change my mind: publish every site, make Revoke mean no access, and give me real per-site takings as a CSV."
