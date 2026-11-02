#!/usr/bin/env bash
# Usage: extract-routes.sh <build-log> <routes-out> <prerender-out>
# Pulls "symbol path" rows out of the next build route table, and the sorted
# prerender-manifest route keys. VOID (exit 2) on an empty or short extraction.
set -u
log=$1; routes_out=$2; pre_out=$3
LC_ALL=C.UTF-8 /usr/bin/perl -Mutf8 -CSD -ne 'if (/([○ƒ●◐])\s+(\/\S*)/) { print "$1 $2\n" }' "$log" | LC_ALL=C sort > "$routes_out"
n=$(wc -l < "$routes_out")
echo "route rows: $n"
if [ "$n" -lt 20 ]; then echo "VOID: fewer than 20 route rows" >&2; exit 2; fi
man=frontend/.next/prerender-manifest.json
if [ ! -f "$man" ]; then echo "VOID: $man missing" >&2; exit 2; fi
node -e 'const m=require(process.argv[1]);const k=Object.keys(m.routes||{}).sort();process.stdout.write(k.join("\n")+(k.length?"\n":""));console.error("prerender keys: "+k.length)' "$PWD/$man" > "$pre_out"
