# inv24-browser-headers.awk — INV-24's browser header scanner (issue #1025), read by
# k8s/scripts/check-render-invariants.sh. Input: client-side frontend source files.
# Output: one record per candidate request header:  <name> TAB <file>:<line> TAB <rule>
#
# It fails CLOSED: over-inclusion is the intended direction, and a hit that is not a request
# header is excluded by NAME AND FILE in INV24_ALLOW (check-render-invariants.sh), with its
# reason, never by a pattern. Rules (review round 1 on PR #1026, finding 4, added all but the
# first two):
#   headers-object      every key of a `headers: {...}` / `headers = {...}` object literal
#   headers-set         the name in `<...headers>.set("X", ...)` / `.append("X", ...)`
#   set-header-shaped   the same call on ANY receiver when the name is header-shaped
#   headers-assign      `x.headers.Foo = ...` / `x.headers["Foo"] = ...`
#   bracket-assign      `x["Header-Name"] = ...` on any receiver, header-shaped names
#   new-headers-pair    the first string of each `["Name", value]` pair in `new Headers(...)`
#   new-headers-object  every key of an object passed to `new Headers({...})`
#   string-key          a header-shaped quoted key in property position ANYWHERE
#                       (`const extra = {"X-Foo": v}` passed later as `{ headers: extra }`)
# Header-shaped = hyphenated (`[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)+`) or one of the standard
# single-token request headers below. Comment lines (// /* *) are skipped.
# <name> TAB <file>:<line> TAB <rule>
function hy(s) { return s ~ /^[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)+$/ }
function word(s) { return tolower(s) ~ /^(authorization|prefer|range|priority|traceparent|tracestate|baggage|pragma|forwarded)$/ }
function emit(n, r) { printf "%s\t%s:%d\t%s\n", n, FILENAME, FNR, r }
function grab(t, r,   m) {
    while (match(t, /(^|[{,])[[:space:]]*("[A-Za-z][A-Za-z0-9-]*"|'[A-Za-z][A-Za-z0-9-]*'|[A-Za-z][A-Za-z0-9-]*)[[:space:]]*:/)) {
        m = substr(t, RSTART, RLENGTH); t = substr(t, RSTART + RLENGTH)
        sub(/^[{,]/, "", m); gsub(/[[:space:]"':]/, "", m)
        emit(m, r)
    }
}
function pairs(t,   m) {
    while (match(t, /\[[[:space:]]*["'][A-Za-z][A-Za-z0-9-]*["'][[:space:]]*,/)) {
        m = substr(t, RSTART, RLENGTH); t = substr(t, RSTART + RLENGTH)
        sub(/^\[[[:space:]]*["']/, "", m); sub(/["'][[:space:]]*,$/, "", m)
        emit(m, "new-headers-pair")
    }
}
# walk(t): the text inside the open construct; depth counts o/c characters
function walk(t, o, c,   i, ch, seg) {
    seg = ""
    for (i = 1; i <= length(t); i++) {
        ch = substr(t, i, 1)
        if (ch == o) depth++
        else if (ch == c) { depth--; if (depth <= 0) { mode = ""; return seg } }
        seg = seg ch
    }
    return seg
}
FNR == 1 { mode = ""; depth = 0 }
{
    line = $0
    if (line ~ /^[[:space:]]*(\/\/|\/\*|\*)/) next
    # continuation of a multi-line headers object / new Headers(...)
    if (mode == "obj") { grab(walk(line, "{", "}"), "headers-object"); }
    else if (mode == "hdr") { s = walk(line, "(", ")"); pairs(s); grab(s, "new-headers-object") }
    else {
        if (match(line, /(^|[^A-Za-z0-9_])headers[[:space:]]*[:=][[:space:]]*\{/)) {
            depth = 0; mode = "obj"; grab(walk(substr(line, RSTART + RLENGTH - 1), "{", "}"), "headers-object")
        }
        if (mode == "" && match(line, /new[[:space:]]+Headers[[:space:]]*\(/)) {
            depth = 0; mode = "hdr"; s = walk(substr(line, RSTART + RLENGTH - 1), "(", ")"); pairs(s); grab(s, "new-headers-object")
        }
    }
    # .set/.append("Name" — any name on a headers receiver; a header-shaped name on any receiver
    t = line
    while (match(t, /[A-Za-z0-9_$\]\)]*\.(set|append)\([[:space:]]*["'][A-Za-z][A-Za-z0-9-]*["']/)) {
        m = substr(t, RSTART, RLENGTH); t = substr(t, RSTART + RLENGTH)
        rcv = m; sub(/\.(set|append)\(.*$/, "", rcv)
        n = m; sub(/^.*\.(set|append)\([[:space:]]*["']/, "", n); sub(/["']$/, "", n)
        if (tolower(rcv) ~ /headers$/) emit(n, "headers-set")
        else if (hy(n) || word(n)) emit(n, "set-header-shaped")
    }
    # headers.Foo = / headers["Foo"] =
    t = line
    while (match(t, /[Hh]eaders(\.[A-Za-z][A-Za-z0-9_]*|\[[[:space:]]*["'][A-Za-z][A-Za-z0-9-]*["'][[:space:]]*\])[[:space:]]*=($|[^=>])/)) {
        m = substr(t, RSTART, RLENGTH); t = substr(t, RSTART + RLENGTH)
        sub(/^[Hh]eaders/, "", m); sub(/[[:space:]]*=.*$/, "", m); gsub(/[][."'[:space:]]/, "", m)
        emit(m, "headers-assign")
    }
    # x["Header-Name"] = on any receiver
    t = line
    while (match(t, /\[[[:space:]]*["'][A-Za-z][A-Za-z0-9-]*["'][[:space:]]*\][[:space:]]*=($|[^=>])/)) {
        m = substr(t, RSTART, RLENGTH); t = substr(t, RSTART + RLENGTH)
        sub(/^\[[[:space:]]*["']/, "", m); sub(/["'][[:space:]]*\].*$/, "", m)
        if (hy(m) || word(m)) emit(m, "bracket-assign")
    }
    # "Header-Name": in property position anywhere
    t = line
    while (match(t, /(^|[{,])[[:space:]]*["'][A-Za-z][A-Za-z0-9-]*["'][[:space:]]*:/)) {
        m = substr(t, RSTART, RLENGTH); t = substr(t, RSTART + RLENGTH)
        sub(/^[{,]?[[:space:]]*["']/, "", m); sub(/["'][[:space:]]*:$/, "", m)
        if (hy(m) || word(m)) emit(m, "string-key")
    }
}
