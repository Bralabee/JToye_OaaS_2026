#!/usr/bin/env python3
"""render_paths.py: walk a `kubectl kustomize` render into full key PATHS (#976, round 3).

Usage (PyYAML required; run as `python3 k8s/scripts/render_paths.py <render.yaml | ->`):

    one line per YAML node:   <kind> TAB <metadata.name> TAB <path> TAB <value>

This REPLACES the hand-written awk walker (RENDER_PATHS_AWK) that check-render-invariants.sh
used to carry. That walker re-implemented YAML line by line and was wrong three times in a row
on the same value (INV-20's JAVA_OPTS_APPEND): it read only the first line of a folded scalar,
then a dead regex, then it dropped a continuation line that began with `#` as a comment, which
inside a quoted scalar is part of the value. Every one of those is a hole in a re-implementation
of the YAML grammar, and a fourth would be the same kind. The grammar is now owned by PyYAML.

THE CONTRACT (unchanged; every consumer of the path tables reads exactly this)

  * kind / name   the document's `kind` and `metadata.name` ("" when absent).
  * path          segments joined with "::"; a map key is its text, a sequence item is "[i]",
                  indexed from 0 per parent, so containers::[0]::env::[3]::name is unambiguous.
                  A sequence ITEM that is a mapping has no record of its own, only its keys do.
  * a key whose value is a block mapping or block sequence gets a record with an EMPTY value
    (so "does this key exist" is answerable); a scalar key gets its value.
  * value         the COMPLETE scalar as YAML parses it (a folded or multi-line scalar is whole,
                  with a comment character, a quote or a colon in it left alone), written back in
                  the RAW spelling the awk walker returned, because the consumers distinguish
                  the string "true" from the boolean true and unquote exactly once (unq):
                    plain scalar         the text                         true   10.0.0.0/8
                    single-quoted        '...' with ' doubled             'it''s'
                    double-quoted        "..." with \\ and \" escaped     "a\"b"
                  A scalar that carries a TAB, a line break or another control character is
                  written double-quoted with \\t \\n \\r \\xNN escapes whatever its source style,
                  so a value can never break the TSV (the awk walker had no encoding for this).
                  A key containing one of those characters is escaped the same way (unquoted).
  * block scalar (| or >)   the indicator followed by the double-quoted escaped content
                  (e.g. `|"line1\nline2\n"`, `|-"..."`). INV-20 still detects the indicator
                  prefix and refuses a block scalar in Keycloak's command, while INV-15
                  (configuration-snippet) and INV-16 (truststore-builder command) read the
                  parsed lines from the path table without re-parsing raw YAML in awk.
  * flow collection ([..] / {..})   one record whose value is the flow text with each scalar
                  written as above; the walk does not descend, as before. `kubectl kustomize`
                  emits flow style only for empty collections: `{}` and `[]`.

Unlike the awk walker, a key outside [A-Za-z0-9_./-] (quoted, with a space, a colon, a `$`) is
walked rather than skipped, and `metadata.name` is the name, not the first `  name:` line.

EXIT: 0 ok; 2 VOID with a `PARSE ERROR:` line on stderr (PyYAML missing, unreadable input, a
document YAML rejects, a document that is not a mapping, a complex key). Callers must treat any
non-zero status as VOID: the output of a failed run is never to be judged.
"""
import sys


def void(msg):
    sys.stderr.write("PARSE ERROR: render_paths.py: %s\n" % msg)
    sys.exit(2)


try:
    import yaml
except ImportError:
    void("PyYAML is not importable by this python3; the render cannot be walked. "
         "Install PyYAML (or run under the conda env that has it); there is no fallback walker.")


def esc_ctrl(s):
    """Double-quote escape for control characters; the TSV separators can never survive."""
    out = []
    for c in s:
        o = ord(c)
        if c == "\\":
            out.append("\\\\")
        elif c == '"':
            out.append('\\"')
        elif c == "\t":
            out.append("\\t")
        elif c == "\n":
            out.append("\\n")
        elif c == "\r":
            out.append("\\r")
        elif o < 0x20 or o == 0x7F:
            out.append("\\x%02x" % o)
        else:
            out.append(c)
    return "".join(out)


def has_ctrl(s):
    return any(ord(c) < 0x20 or ord(c) == 0x7F for c in s)


def block_indicator(node):
    v = node.value
    if v == "":
        chomp = ""
    elif v.endswith("\n\n"):
        chomp = "+"
    elif v.endswith("\n"):
        chomp = ""
    else:
        chomp = "-"
    return node.style + chomp


def scalar_text(node):
    """A scalar in the raw spelling the consumers expect."""
    v, st = node.value, node.style
    if st in ("|", ">"):
        return block_indicator(node) + '"' + esc_ctrl(v) + '"'
    if st == '"' or has_ctrl(v):
        return '"' + esc_ctrl(v) + '"'
    if st == "'":
        return "'" + v.replace("'", "''") + "'"
    return v


def key_text(knode):
    if not isinstance(knode, yaml.ScalarNode):
        void("a mapping key that is not a scalar; refusing to invent a path for it")
    return esc_ctrl(knode.value) if has_ctrl(knode.value) else knode.value


def flow_text(node):
    if isinstance(node, yaml.ScalarNode):
        return scalar_text(node)
    if isinstance(node, yaml.SequenceNode):
        return "[" + ", ".join(flow_text(i) for i in node.value) + "]"
    return "{" + ", ".join(flow_text(k) + ": " + flow_text(v) for k, v in node.value) + "}"


class Walker:
    def __init__(self, out, kind, name):
        self.out, self.kind, self.name = out, kind, name

    def emit(self, segs, value):
        self.out.append("%s\t%s\t%s\t%s\n" % (self.kind, self.name, "::".join(segs), value))

    def value(self, segs, node):
        if isinstance(node, yaml.ScalarNode):
            self.emit(segs, scalar_text(node))
        elif node.flow_style:
            self.emit(segs, flow_text(node))
        else:
            self.emit(segs, "")
            self.children(segs, node)

    def children(self, segs, node):
        if isinstance(node, yaml.MappingNode):
            for k, v in node.value:
                self.value(segs + [key_text(k)], v)
        else:
            for i, item in enumerate(node.value):
                self.item(segs + ["[%d]" % i], item)

    def item(self, segs, node):
        # A mapping item has no record of its own: only its keys do.
        if isinstance(node, yaml.MappingNode) and not node.flow_style:
            self.children(segs, node)
        else:
            self.value(segs, node)


def mapping_get(node, key):
    for k, v in node.value:
        if isinstance(k, yaml.ScalarNode) and k.value == key:
            return v
    return None


def main(argv):
    if len(argv) != 2:
        void("usage: render_paths.py <render.yaml | ->")
    try:
        data = sys.stdin.buffer.read() if argv[1] == "-" else open(argv[1], "rb").read()
    except OSError as e:
        void("cannot read %s: %s" % (argv[1], e))
    out = []
    try:
        for doc in yaml.compose_all(data, Loader=yaml.SafeLoader):
            if doc is None:
                continue
            if isinstance(doc, yaml.ScalarNode) and doc.value == "" and doc.style is None:
                continue  # an empty document between two '---'
            if not isinstance(doc, yaml.MappingNode):
                void("a document that is not a mapping (a %s); every Kubernetes object is one"
                     % type(doc).__name__)
            kn = mapping_get(doc, "kind")
            kind = scalar_text(kn) if isinstance(kn, yaml.ScalarNode) else ""
            md = mapping_get(doc, "metadata")
            nn = mapping_get(md, "name") if isinstance(md, yaml.MappingNode) else None
            name = scalar_text(nn) if isinstance(nn, yaml.ScalarNode) else ""
            Walker(out, kind, name).children([], doc)
    except yaml.YAMLError as e:
        void("YAML rejected %s: %s" % (argv[1], str(e).replace("\n", " ")))
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    sys.stdout.write("".join(out))
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
