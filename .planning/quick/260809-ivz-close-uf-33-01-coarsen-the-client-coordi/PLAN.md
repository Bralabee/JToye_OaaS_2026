---
quick_id: 260809-ivz
slug: close-uf-33-01-coarsen-the-client-coordi
type: quick
autonomous: true
files_modified:
  - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
  - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceGeocodeTest.java
---

<objective>
Close UF-33-01 (33-SECURITY.md, WARNING): the WR-03 `client_coordinate_rejected` WARN at
`ShopService.java:508-511` logs the raw vendor-supplied lat/lon pair. On the REJECTED branch that
pair never becomes public and could be a residential position (a home kitchen's real location), so
logging it at full precision widens T-33-05-04's declared "slug and extracted postcode only" surface.
The ACCEPTED branch stays untouched — that value is published on the ranking surface anyway.
</objective>

<tasks>
<task type="auto">
  <name>Task 1: coarsen the rejected-branch WARN to integer degrees, test-first</name>
  <files>core-java/src/test/java/uk/jtoye/core/shop/ShopServiceGeocodeTest.java, core-java/src/main/java/uk/jtoye/core/shop/ShopService.java</files>
  <action>
1. Add a test arm (ListAppender pattern, as the Belfast accepted-arm at :477-499): create a shop
   with the New York pair (40.7128, -74.0060); assert the WARN carries
   event=client_coordinate_rejected, does NOT contain "40.7128" or "-74.006" (the raw pair), and
   DOES contain the integer-degree forms "41" and "-74" (positive control that the operator still
   sees roughly where the rejected pair pointed).
2. Run the test against the UNFIXED tree — it MUST fail on the raw-pair assertions. Record the output.
3. Coarsen the rejected-branch log call: Math.round(...) on both values, message updated to say the
   values are integer-degree (~111 km) coarse, citing UF-33-01.
4. Re-run: new arm green, full ShopServiceGeocodeTest class green.
  </action>
  <verify>Fail direction recorded at step 2; clean direction = whole class green after the fix.</verify>
</task>
</tasks>
