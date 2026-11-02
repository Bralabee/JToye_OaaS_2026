#!/usr/bin/env bash
# render-values.sh ROOT — render k8s/{staging,production,local} under ROOT and check the featured values.
root=${1:?root}; R=$(mktemp -d)
for o in staging production local; do kubectl kustomize "$root/k8s/$o" > "$R/$o.yaml" 2>"$R/$o.err"; echo "kustomize $o rc=$?"; done
for o in staging production local; do echo "[$o] value: $(rg -n 'storefront\.featured-shop-slugs:' "$R/$o.yaml" | head -3)"; echo "[$o] env JTOYE_FEATURED_SHOP_SLUGS count: $(rg -c 'name: JTOYE_FEATURED_SHOP_SLUGS' "$R/$o.yaml")"; done
s=0
rg -q 'storefront\.featured-shop-slugs: "?mama-ades-kitchen,peckham-jollof-co,brixton-village-grill"?$' "$R/staging.yaml" || { echo "FAIL staging value"; s=1; }
rg -q 'storefront\.featured-shop-slugs: ""$' "$R/production.yaml" || { echo "FAIL production value not empty"; s=1; }
rg -q 'storefront\.featured-shop-slugs: ""$' "$R/local.yaml" || { echo "FAIL local value not empty"; s=1; }
for o in staging production local; do rg -q 'name: JTOYE_FEATURED_SHOP_SLUGS' "$R/$o.yaml" || { echo "FAIL $o env missing"; s=1; }; done
echo "render-value check rc=$s"; exit $s
