# `uk.jtoye.gatefixtures` — deliberately-broken inputs, kept outside `uk.jtoye.core`

Every class in this package is a **deliberate violation of some architectural rule**. They exist so
that the gates asserting those rules can be shown to FAIL, permanently and in CI, rather than being
observed only passing. A gate observed only passing may be incapable of failing.

**Do not copy anything in here into production code, and do not "fix" these classes.** Making them
compliant silently disables the falsification half of the gate that consumes them, which is worse
than deleting the gate outright (the gate would stay green and look like evidence).

## Why the package sits outside `uk.jtoye.core`

Two independent reasons, both load-bearing:

1. **Spring's component scan starts at `uk.jtoye.core`** (from `@SpringBootApplication` on
   `uk.jtoye.core.CoreApplication`). A `@RestController` fixture under that tree would be a real
   bean in every `@SpringBootTest` context, needing its dependencies satisfied and registering a
   handler mapping.
2. **The gates themselves scan `uk.jtoye.core`.** A fixture inside that tree would be found by the
   very scan it is meant to falsify, so the gate would be permanently RED on a correct tree — the
   recorded "expected-0 that is actually 1 on a correct tree" trap. Keeping fixtures out of the
   scanned package means the gate can only ever go red because of real production code.

Consumers therefore hand these classes to the gate's detector **directly**, never via the scan.
