#!/usr/bin/env node
/*
 * count-test-blocks.mjs — count the test blocks a JS/TS runner will actually execute.
 *
 * WHY THIS EXISTS (issue #291)
 *
 *   scripts/docs-freshness.sh used to count Jest/Playwright/vitest blocks with the
 *   textual regex `\b(it|test)\(`. That regex is wrong in BOTH directions at once,
 *   and the two errors partially cancel, which is why it survived: the gate stayed
 *   self-consistent (manifest == computed) and could never go red on its own defect.
 *
 *     OVER-counts. `\b` treats `.` as a word boundary, so `RegExp.prototype.test(`
 *     matches. Measured 2026-08-05 on this tree: 7 phantom Jest blocks (4 in
 *     frontend/__tests__/link-graph.test.ts, 1 each in the kitchen page test,
 *     storefront-nav-basket-plural and storefront-server) and 5 phantom Playwright
 *     blocks (kitchen-flow x2, storefront-dish-modal-a11y, storefront-flows,
 *     storefront-ssr-seo). Every one of them is `/some-regex/.test(x)`.
 *
 *     UNDER-counts. A table-driven `it.each([...])("…")` has no `it(` in it at all,
 *     so N executed tests contribute ZERO. Measured on this tree: 9 `it.each` sites
 *     expanding to 51 tests that the manifest could not see.
 *
 *   Net on 2026-08-05: manifest 745, `npx jest` 789. 745 - 7 + 51 = 789.
 *
 * WHAT IT DOES
 *
 *   1. Masks comments, string/template/regex literals (length-preserving) so a token
 *      in prose or inside a string can never be counted as a declaration.
 *   2. Finds `it` / `test` heads at a NON-member position (so `.test(` is excluded),
 *      then reads the modifier chain that follows (`.each`, `.only`, `.skip`, …).
 *   3. Classifies the chain against a per-family policy, and for `.each` resolves the
 *      table to a row count — an inline array literal, or an array-literal `const`
 *      declared in the same file (that is the `it.each(hostile)` shape in
 *      frontend/app/api/customer-auth/__tests__/logout-url-origin.test.ts).
 *
 * FAIL-CLOSED. Anything it cannot resolve — an unknown modifier chain, a tagged-
 * template `it.each` table, a `describe.each`, an `xit`/`fit` alias, an `.each`
 * argument that is a call or an import — exits 2 (VOID) with the file and line. It
 * never guesses a number. "Could not count it" is never rendered as a count.
 *
 * KNOWN LIMIT, and its backstop. A test declared inside a `for` loop or a `forEach`
 * is one declaration and N executions, and no static reader can resolve that in
 * general. This script therefore cannot be the last word. The last word is
 * scripts/check-test-count-oracle.sh, which asserts docs/metrics.json against each
 * runner's own report in the CI jobs where those runners already execute.
 * Static counter first because it is cheap; runner oracle second because it is true.
 *
 * Its own refusals are falsified by scripts/check-test-block-counter.sh — because a
 * measurement that cannot be shown to fail is what produced #291 in the first place.
 *
 * Usage:
 *   node scripts/count-test-blocks.mjs --family <jest|vitest|playwright> <file>...
 *   node scripts/count-test-blocks.mjs --family jest --per-file <file>...
 *
 * Output (stdout, one line): {"blocks":N,"files":M}
 *   `files` counts input files with at least one block — the same thing the old
 *   `grep -l` file counts meant.
 * Exit: 0 = counted, 2 = VOID (could not count something; nothing is printed).
 */

import { readFileSync } from "node:fs";

// Masked-out character. Deliberately NOT whitespace (so a masked element still
// reads as content when counting array rows), not a word character, and not a
// bracket or comma (so it can never invent or destroy a boundary).
const FILL = String.fromCharCode(1);

// ---------------------------------------------------------------------------
// Per-family policy. A modifier chain that is in none of these sets is a VOID.
//
//   declaration — declares exactly one test
//   each        — declares one test per row of its table argument
//   ignore      — declares no test (hooks, describes, runtime directives)
//   dual        — Playwright's `test.skip(...)` is a DECLARATION when its first
//                 argument is a string literal (`test.skip("name", fn)`) and a
//                 runtime SKIP DIRECTIVE otherwise (`test.skip(cond, "reason")`).
//                 Getting this wrong in the safe-looking direction would silently
//                 drop real tests, so it is decided by inspecting the argument.
// ---------------------------------------------------------------------------
const JEST_LIKE = {
  declaration: new Set([
    "", "only", "skip", "todo", "failing", "concurrent", "concurrent.only",
    "concurrent.skip", "only.failing", "skip.failing", "fails",
  ]),
  each: new Set([
    "each", "only.each", "skip.each", "concurrent.each", "concurrent.only.each",
    "failing.each", "todo.each",
  ]),
  ignore: new Set([]),
  dual: new Set([]),
};

const POLICY = {
  jest: JEST_LIKE,
  vitest: JEST_LIKE,
  playwright: {
    declaration: new Set(["", "only"]),
    each: new Set(["each", "only.each"]),
    ignore: new Set([
      "describe", "describe.only", "describe.skip", "describe.fixme",
      "describe.serial", "describe.parallel", "describe.configure",
      "describe.serial.only", "describe.parallel.only",
      "use", "beforeEach", "afterEach", "beforeAll", "afterAll",
      "step", "setTimeout", "info", "extend", "expect", "slow",
    ]),
    dual: new Set(["skip", "fixme", "fail"]),
  },
};

// Aliases no family below handles. Present in neither tree today; if one appears the
// count would silently change, so refuse rather than drift.
const FORBIDDEN_ALIASES = /(?<![.\w$])(xit|fit|xtest|ftest|xdescribe|fdescribe)\s*\(/;

class Void extends Error {}

function fail(file, line, msg) {
  throw new Void(`${file}:${line}: ${msg}`);
}

const lineOf = (src, idx) => src.slice(0, idx).split("\n").length;

// ---------------------------------------------------------------------------
// mask(): blank out comments and string/template/regex literals, preserving length
// and newlines so every index and line number still refers to the original file.
// String and template DELIMITERS are kept, so a tagged-template `it.each` table is
// still visible to the scanner (and refused there) rather than vanishing.
// ---------------------------------------------------------------------------
const REGEX_PRECEDERS = new Set("(,=:[!&|?{};+-*%^~<>".split(""));
const REGEX_KEYWORDS =
  /\b(return|typeof|instanceof|in|of|new|delete|void|case|do|else|yield|await)$/;

function mask(src) {
  const out = Array.from(src);
  const blank = (from, to) => {
    for (let k = from; k < to; k++) if (out[k] !== "\n") out[k] = FILL;
  };

  let i = 0;
  let lastSig = ""; // last significant (non-space, non-masked) code character
  // Stack of template-literal contexts we are suspended inside while in a `${}`.
  const tplStack = [];
  let braceDepth = 0;

  while (i < src.length) {
    const c = src[i];
    const c2 = src[i + 1];

    // line comment
    if (c === "/" && c2 === "/") {
      let j = i;
      while (j < src.length && src[j] !== "\n") j++;
      blank(i, j);
      i = j;
      continue;
    }
    // block comment
    if (c === "/" && c2 === "*") {
      const end = src.indexOf("*/", i + 2);
      const j = end === -1 ? src.length : end + 2;
      blank(i, j);
      i = j;
      continue;
    }
    // single/double quoted string
    if (c === "'" || c === '"') {
      let j = i + 1;
      while (j < src.length) {
        if (src[j] === "\\") { j += 2; continue; }
        if (src[j] === c || src[j] === "\n") break;
        j++;
      }
      blank(i + 1, j);
      i = j + 1;
      lastSig = c;
      continue;
    }
    // template literal — keep the backticks, mask the text, recurse into `${ }`
    if (c === "`") {
      let j = i + 1;
      while (j < src.length) {
        if (src[j] === "\\") { j += 2; continue; }
        if (src[j] === "`") break;
        if (src[j] === "$" && src[j + 1] === "{") break;
        j++;
      }
      blank(i + 1, j);
      if (src[j] === "$") {
        // enter the substitution as code; the matching `}` resumes the template
        tplStack.push(braceDepth);
        braceDepth++;
        i = j + 2;
        lastSig = "{";
        continue;
      }
      i = j + 1;
      lastSig = "`";
      continue;
    }
    if (c === "{") { braceDepth++; lastSig = c; i++; continue; }
    if (c === "}") {
      braceDepth--;
      if (tplStack.length && tplStack[tplStack.length - 1] === braceDepth) {
        // closing a `${}` — resume masking the enclosing template literal
        tplStack.pop();
        let j = i + 1;
        while (j < src.length) {
          if (src[j] === "\\") { j += 2; continue; }
          if (src[j] === "`") break;
          if (src[j] === "$" && src[j + 1] === "{") break;
          j++;
        }
        blank(i + 1, j);
        if (src[j] === "$") { tplStack.push(braceDepth); braceDepth++; i = j + 2; lastSig = "{"; continue; }
        i = j + 1;
        lastSig = "`";
        continue;
      }
      lastSig = c; i++; continue;
    }
    // regex literal vs division
    if (c === "/") {
      const before = src.slice(0, i).replace(/\s+$/, "");
      const isRegex = lastSig === "" || REGEX_PRECEDERS.has(lastSig) || REGEX_KEYWORDS.test(before);
      if (isRegex) {
        // A regex literal cannot span a newline. If no unescaped, non-class `/`
        // closes it on this line, it was division after all — do not mask.
        let j = i + 1, inClass = false, closed = -1;
        while (j < src.length && src[j] !== "\n") {
          if (src[j] === "\\") { j += 2; continue; }
          if (src[j] === "[") inClass = true;
          else if (src[j] === "]") inClass = false;
          else if (src[j] === "/" && !inClass) { closed = j; break; }
          j++;
        }
        if (closed !== -1) {
          blank(i + 1, closed);
          i = closed + 1;
          lastSig = "/";
          continue;
        }
      }
      lastSig = c; i++; continue;
    }
    if (!/\s/.test(c)) lastSig = c;
    i++;
  }
  return out.join("");
}

// ---------------------------------------------------------------------------
// Small parsing helpers over the MASKED text.
// ---------------------------------------------------------------------------
function matchParen(s, open) {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    if (s[i] === "(") depth++;
    else if (s[i] === ")") { depth--; if (depth === 0) return i; }
  }
  return -1;
}

// Number of top-level elements of an array literal whose text starts at `s[0] === '['`.
function countArrayElements(s) {
  let depth = 0, commas = 0, sawContent = false, lastWasComma = false;
  for (let i = 1; i < s.length; i++) {
    const c = s[i];
    if (c === "[" || c === "(" || c === "{") { depth++; sawContent = true; lastWasComma = false; continue; }
    if (c === "]" && depth === 0) break;
    if (c === "]" || c === ")" || c === "}") { depth--; sawContent = true; lastWasComma = false; continue; }
    if (c === "," && depth === 0) { commas++; lastWasComma = true; continue; }
    if (!/\s/.test(c)) { sawContent = true; lastWasComma = false; }
  }
  if (!sawContent) return 0;
  return lastWasComma ? commas : commas + 1;
}

// Locate `const|let|var NAME <type?> = [ … ]` in the same file and return the array
// text. Returns null when the binding does not exist or is not an array literal —
// the caller turns that into a VOID rather than into a guessed row count.
//
// The TypeScript annotation between the name and the `=` may itself contain `=`
// characters (`Record<string, () => void>`), so the initialiser is found by scanning
// for an `=` that is at bracket depth 0 and is not part of `==`, `=>`, `>=`, `<=`,
// `!=`. A `;` at depth 0 first means this binding has no initialiser.
function resolveArrayBinding(masked, name) {
  const re = new RegExp(`(?<![.\\w$])(?:const|let|var)\\s+${name}(?![\\w$])`, "g");
  let m;
  while ((m = re.exec(masked)) !== null) {
    let i = re.lastIndex;
    let depth = 0;
    let found = -1;
    while (i < masked.length) {
      const c = masked[i];
      if (c === "<" || c === "(" || c === "[" || c === "{") depth++;
      else if (c === ">" || c === ")" || c === "]" || c === "}") depth--;
      else if (c === ";" && depth <= 0) break;
      else if (
        c === "=" && depth <= 0 &&
        masked[i + 1] !== "=" && masked[i + 1] !== ">" &&
        masked[i - 1] !== "=" && masked[i - 1] !== "!" &&
        masked[i - 1] !== "<" && masked[i - 1] !== ">"
      ) { found = i; break; }
      i++;
    }
    if (found === -1) continue;
    i = found + 1;
    while (i < masked.length && /\s/.test(masked[i])) i++;
    if (masked[i] === "[") return masked.slice(i);
  }
  return null;
}

// ---------------------------------------------------------------------------
// The scanner.
// ---------------------------------------------------------------------------
function countFile(file, family) {
  const src = readFileSync(file, "utf8");
  const masked = mask(src);
  const policy = POLICY[family];

  const alias = FORBIDDEN_ALIASES.exec(masked);
  if (alias) {
    fail(file, lineOf(masked, alias.index),
      `unsupported test alias '${alias[1]}' — extend scripts/count-test-blocks.mjs rather than letting the count drift`);
  }
  const dEach = /(?<![.\w$])describe\s*\.\s*each\s*[(`]/.exec(masked);
  if (dEach) {
    fail(file, lineOf(masked, dEach.index),
      "describe.each multiplies every block inside it; this counter cannot resolve that statically");
  }

  let blocks = 0;
  const head = /(?<![.\w$])(it|test)(?![\w$])/g;
  let m;
  while ((m = head.exec(masked)) !== null) {
    const start = m.index;

    // member access, possibly with whitespace: `foo\n  .test(`
    let b = start - 1;
    while (b >= 0 && /\s/.test(masked[b])) b--;
    if (b >= 0 && masked[b] === ".") continue;

    // read the modifier chain
    let i = start + m[1].length;
    const chain = [];
    for (;;) {
      let j = i;
      while (j < masked.length && /\s/.test(masked[j])) j++;
      if (masked[j] !== ".") break;
      j++;
      while (j < masked.length && /\s/.test(masked[j])) j++;
      const id = /^[A-Za-z_$][\w$]*/.exec(masked.slice(j));
      if (!id) break;
      chain.push(id[0]);
      i = j + id[0].length;
    }
    let k = i;
    while (k < masked.length && /\s/.test(masked[k])) k++;
    const delim = masked[k];
    if (delim !== "(" && delim !== "`") continue; // a bare `it` / `test` identifier

    const key = chain.join(".");
    const line = lineOf(masked, start);

    if (policy.ignore.has(key)) continue;

    if (policy.each.has(key)) {
      if (delim === "`") {
        fail(file, line, "tagged-template `it.each` table — this counter only resolves array tables");
      }
      const close = matchParen(masked, k);
      if (close === -1) fail(file, line, "unbalanced parentheses after .each(");
      const arg = masked.slice(k + 1, close).trim();
      if (arg.startsWith("[")) {
        blocks += countArrayElements(arg);
        continue;
      }
      if (/^[A-Za-z_$][\w$]*$/.test(arg)) {
        const bound = resolveArrayBinding(masked, arg);
        if (bound === null) {
          fail(file, line, `.each(${arg}) — '${arg}' is not an array literal declared in this file`);
        }
        blocks += countArrayElements(bound);
        continue;
      }
      fail(file, line, `.each(…) table is not a resolvable array literal: ${arg.slice(0, 60)}`);
    }

    if (policy.dual.has(key)) {
      if (delim === "`") fail(file, line, `unsupported tagged-template form: ${m[1]}.${key}\``);
      const close = matchParen(masked, k);
      if (close === -1) fail(file, line, `unbalanced parentheses after ${m[1]}.${key}(`);
      const arg = masked.slice(k + 1, close).trim();
      // A string-literal first argument means this is a declaration, not a directive.
      if (arg.startsWith('"') || arg.startsWith("'") || arg.startsWith("`")) blocks += 1;
      continue;
    }

    if (policy.declaration.has(key)) {
      if (delim === "`") fail(file, line, `unsupported tagged-template form: ${m[1]}\``);
      blocks += 1;
      continue;
    }

    fail(file, line,
      `unknown ${family} modifier chain '${m[1]}${key ? "." + key : ""}(' — add it to POLICY in scripts/count-test-blocks.mjs`);
  }
  return blocks;
}

// ---------------------------------------------------------------------------
function main(argv) {
  let family = null;
  let perFile = false;
  let fromStdin = false;
  const files = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--family") { family = argv[++i]; continue; }
    if (argv[i] === "--per-file") { perFile = true; continue; }
    if (argv[i] === "--stdin") { fromStdin = true; continue; }
    files.push(argv[i]);
  }
  if (!family || !POLICY[family]) {
    process.stderr.write(`VOID: --family must be one of ${Object.keys(POLICY).join("|")}\n`);
    return 2;
  }
  // --stdin takes a newline-separated file list. Callers use it instead of xargs:
  // xargs silently SPLITS an over-long list into several invocations, each of which
  // would print its own partial total and the caller would read only one of them.
  if (fromStdin) {
    let raw = "";
    try { raw = readFileSync(0, "utf8"); } catch { raw = ""; }
    for (const line of raw.split("\n")) if (line.trim()) files.push(line.trim());
  }
  if (files.length === 0) {
    process.stderr.write("VOID: no input files — a count of 0 over an empty set is not a measurement\n");
    return 2;
  }

  let blocks = 0;
  let filesWithBlocks = 0;
  const rows = [];
  try {
    for (const f of files) {
      const n = countFile(f, family);
      blocks += n;
      if (n > 0) filesWithBlocks++;
      if (perFile) rows.push(`${n}\t${f}`);
    }
  } catch (e) {
    if (e instanceof Void) {
      process.stderr.write(`VOID: ${e.message}\n`);
      return 2;
    }
    process.stderr.write(`VOID: ${e && e.stack ? e.stack : e}\n`);
    return 2;
  }

  if (perFile) process.stdout.write(rows.join("\n") + (rows.length ? "\n" : ""));
  process.stdout.write(JSON.stringify({ blocks, files: filesWithBlocks }) + "\n");
  return 0;
}

process.exit(main(process.argv.slice(2)));
