#!/usr/bin/env bash
# Extract distinct com.fasterxml.jackson group:artifact RESOLVED version from a
# `gradle dependencies` report. Resolved = token after "->" when present, else the
# third colon field. Suffixes (*) (c) (n) stripped.
set -u
f="$1"
[ -s "$f" ] || { echo "VOID: empty or missing input $f" >&2; exit 2; }
awk '
{
  for (i = 1; i <= NF; i++) {
    if ($i ~ /^com\.fasterxml\.jackson/) {
      n = split($i, p, ":")
      ga = p[1] ":" p[2]
      v = (n >= 3) ? p[3] : ""
      if ($(i+1) == "->") v = $(i+2)
      gsub(/[()*]/, "", v)
      print ga ":" v
    }
  }
}' "$f" | sort -u
