# Test manifest — offline stand-in for the generated geo/SOURCE.md

Shaped exactly like the real generated manifest so `PostcodeCentroidImporter` exercises its REAL
parsing path in tests. A test that bypassed the parse would leave the one piece of string handling
in the importer completely unexercised — and its failure mode is refusing to boot.

| | |
|---|---|
| **Dataset** | fixture (8 real Code-Point Open rows) |
| **Rows after filter** | **8** (dropped 0) |
