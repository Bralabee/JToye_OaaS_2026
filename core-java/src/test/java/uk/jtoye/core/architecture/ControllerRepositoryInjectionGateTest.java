package uk.jtoye.core.architecture;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.gatefixtures.CompliantControllerFixture;
import uk.jtoye.gatefixtures.ControllerInjectingRepositoryFixture;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Issue #501 — <b>no {@code @RestController} may hold a direct data-access dependency.</b>
 * Controllers reach the database through a {@code @Transactional} service, never a repository or
 * an {@code EntityManager} of their own.
 *
 * <h2>Why this rule, and why a gate rather than a note</h2>
 *
 * Issue #444: {@code WebhookDeliveryController} injected {@code WebhookDeliveryRepository} directly
 * and read it with no transaction anywhere on the path. {@code TenantSetLocalAspect} opens with
 * {@code if (!TransactionSynchronizationManager.isActualTransactionActive()) return;}, so the
 * {@code app.current_tenant_id} GUC was never pinned; the FORCE-RLS policy then correctly matched
 * no rows. <b>The vendor webhook delivery log was empty for an entire milestone and nothing
 * noticed</b>, because under RLS this failure mode presents as <i>"no data"</i>, not as an error.
 * An empty page is indistinguishable from a genuinely empty log — the defect is silent by
 * construction, which is precisely the kind a test cannot be relied on to catch by accident.
 *
 * <h2>Why reflection, and not a text search — this is load-bearing</h2>
 *
 * The obvious implementation is {@code grep -l Repository core-java/**}{@code /*Controller.java}.
 * Measured on the tree at the time this gate was written, that implementation is wrong in BOTH
 * directions at once:
 *
 * <ul>
 *   <li><b>False positive.</b> It reports {@code WebhookDeliveryController} — a file with no
 *       repository dependency at all — because the class Javadoc explaining the #444 removal
 *       contains {@code WebhookDeliveryRepository}. This is the recorded trap where a rule that
 *       must NAME the thing it forbids fires on its own explanation, and it means the text gate
 *       would be RED on a CORRECT tree. A gate that is red when the code is right gets deleted.</li>
 *   <li><b>False negatives.</b> Scoping by the {@code *Controller.java} filename misses
 *       {@code uk.jtoye.core.CoreApplication}, which really is annotated {@code @RestController}.
 *       Scoping by the text {@code @RestController} instead over-matches
 *       {@code @RestControllerAdvice} (a prefix, and not the same stereotype) and matches three
 *       more files that merely mention the annotation inside a comment.</li>
 * </ul>
 *
 * Reflection has neither problem: it reads the annotation that is actually present on the compiled
 * class and the type a field/parameter actually has, so comments, Javadoc {@code {@link}} tags and
 * annotation-name prefixes are invisible to it.
 *
 * <h2>...and not Spring's own component scanner either — a measured hole</h2>
 *
 * The first cut of this gate enumerated controllers with
 * {@code ClassPathScanningCandidateComponentProvider} + an {@code AnnotationTypeFilter}, which is
 * the conventional way to ask Spring "which classes are {@code @RestController}". It silently
 * returned 23 classes and <b>omitted {@code uk.jtoye.core.tenant.DevTenantController}</b>, which is
 * annotated {@code @RestController} and is production source. The scanner evaluates
 * {@code @Conditional} metadata, and {@code @Profile({"dev", "local"})} does not match the plain
 * {@code StandardEnvironment} a test-constructed scanner carries — so the class was skipped as
 * "not a candidate component". Nothing in the output said so.
 *
 * <p>That hole matters more than its size suggests: a dev/admin-profile controller is exactly the
 * kind that gets a repository wired straight into it, and a gate that cannot see it would have
 * reported a clean tree. So enumeration here is done from the {@code src/main/java} source tree
 * instead — every production top-level class plus its nested classes, loaded WITHOUT initialisation
 * and filtered on the reflected annotation. That is condition-agnostic (no profile can hide a
 * class), main-only by construction, and finds nested controllers. {@code DevTenantController} is
 * pinned as a named positive control so this specific regression cannot come back unnoticed.
 *
 * <h2>Why this gate cannot match itself</h2>
 *
 * It matches on <i>reflected annotations and reflected member types</i>, never on source text, so
 * naming {@code Repository} throughout this file's own prose is inert — this file could contain the
 * word ten thousand times and the gate would not notice. Beyond that, enumeration walks
 * {@code src/main/java} only, and the deliberately broken fixture it uses to prove it can fail
 * lives in {@code core-java/src/test/java/uk/jtoye/gatefixtures} — under neither
 * {@code src/main/java} nor the {@code uk.jtoye.core} package Spring component-scans.
 * {@link #theScanIsRestrictedToMainAndExcludesTheGatesOwnFixtures()} asserts that rather than
 * assuming it.
 *
 * <h2>Exemption policy (#501 acceptance criterion)</h2>
 *
 * Exemptions ARE permitted, via {@link #EXEMPT_CONTROLLERS}, because an un-exemptable gate that
 * later needs an exemption becomes a gate somebody deletes. The set is EMPTY today. It is kept
 * honest by {@link #exemptionsMustBeLiveAndStillNecessary()}, which fails on an exemption naming a
 * class that no longer exists, is no longer a controller, or no longer violates — so a stale
 * exemption cannot quietly go on suppressing a real, newly-introduced violation.
 *
 * <h2>The sibling rule, decided rather than silently dropped (#501 acceptance criterion)</h2>
 *
 * #501 also asks whether to assert that no {@code @RestController} <i>method</i> performs
 * repository access without an active transaction. <b>Decision: deliberately OUT OF SCOPE here,
 * and not attempted in a weaker form.</b> That property is about the runtime call graph — which
 * method reaches which repository, through how many layers, under whose transaction — and neither
 * reflection over declared members nor text search can answer it (the recorded measurement: every
 * true caller of {@code EmailChannel.deliver} is invisible to every text search, because dispatch
 * goes through an interface). Answering it properly needs bytecode call-graph analysis, i.e.
 * ArchUnit or equivalent, which is a new dependency with its own Trivy/Dependabot surface in this
 * repo. The dependency-free rule enforced here is the reachable and complete proxy for it: a
 * controller that holds no repository and no {@code EntityManager} has no way to perform
 * untransacted data access in the first place, because it has nothing to perform it against.
 *
 * <h2>Failing closed</h2>
 *
 * "Found nothing" is never "clean". {@link #theScanSeesTheRealControllerSurface()} fails if the
 * scan returns implausibly few controllers or loses either named positive control, so a scan that
 * silently stops seeing the codebase cannot be reported as a pass.
 */
class ControllerRepositoryInjectionGateTest {

    /**
     * Documented, deliberate exemption mechanism (#501). EMPTY — no controller is exempt today.
     *
     * <p>To add one: put the fully-qualified class name here WITH a comment giving the issue
     * number and why a {@code @Transactional} service is genuinely not viable for it. Note that
     * "the query is read-only" is NOT a reason — a read is exactly the #444 case, because RLS
     * denies the read silently.
     */
    static final Set<String> EXEMPT_CONTROLLERS = Set.of();

    /**
     * Below this, enumeration is broken rather than the tree clean. Measured 24
     * {@code @RestController} classes under {@code src/main/java} when this gate was written; the
     * floor sits well under that so ordinary deletions do not trip it, but far enough above zero
     * that an enumeration returning nothing (wrong source root, empty classpath, changed output
     * layout) fails instead of reporting a vacuous pass.
     */
    private static final int MIN_EXPECTED_MAIN_CONTROLLERS = 15;

    /**
     * Floor on the number of production source files walked. Independent of the controller count:
     * it catches a source root that resolves to a real-but-wrong directory, where the controller
     * count could plausibly land above its own floor by accident.
     */
    private static final int MIN_EXPECTED_MAIN_SOURCE_FILES = 200;

    /**
     * Named positive controls — the seeded matches that prove enumeration sees what it claims to.
     * Each one is here because a plausible implementation MISSES it, measured on this tree:
     * <ul>
     *   <li>{@code CoreApplication} — missed by a {@code *Controller.java} filename glob;</li>
     *   <li>{@code DevTenantController} — missed by Spring's own
     *       {@code ClassPathScanningCandidateComponentProvider}, because {@code @Profile} fails
     *       condition evaluation under a default environment;</li>
     *   <li>{@code WebhookDeliveryController} — the #444 class itself, and the one a text search
     *       for "Repository" wrongly reports as an offender because its Javadoc names the
     *       repository it no longer injects.</li>
     * </ul>
     * If a control is genuinely renamed, update this list — never delete the assertion.
     */
    private static final List<String> SCAN_POSITIVE_CONTROLS = List.of(
            "uk.jtoye.core.CoreApplication",
            "uk.jtoye.core.tenant.DevTenantController",
            "uk.jtoye.core.webhook.WebhookDeliveryController",
            "uk.jtoye.core.shop.ShopController");

    // ------------------------------------------------------------------ model

    /** One offending member, named precisely — #501 requires the class and field, not a count. */
    record Violation(String owner, String memberKind, String memberName, String offendingType) {
        @Override
        public String toString() {
            return owner + " :: " + memberKind + " '" + memberName + "' of type " + offendingType;
        }
    }

    // --------------------------------------------------------------- detector

    /**
     * The three shapes of direct data access. All three share the #444 failure mode: reached from a
     * controller with no surrounding transaction, {@code TenantSetLocalAspect} never pins the tenant
     * GUC and RLS returns nothing without erroring.
     *
     * <p>{@code EntityManager} is included because it is the escape hatch that would satisfy a
     * repository-only rule while reproducing the bug exactly. Verified when this gate was written
     * that no {@code @RestController} injects one, so including it does not redden the tree.
     */
    static boolean isDirectDataAccessType(Class<?> type) {
        return Repository.class.isAssignableFrom(type)
                || type.isAnnotationPresent(org.springframework.stereotype.Repository.class)
                || EntityManager.class.isAssignableFrom(type);
    }

    /** Declared fields and constructor parameters of one class, as violations. */
    static List<Violation> violationsIn(Class<?> controller) {
        List<Violation> found = new ArrayList<>();
        for (Field field : controller.getDeclaredFields()) {
            if (isDirectDataAccessType(field.getType())) {
                found.add(new Violation(controller.getName(), "field", field.getName(),
                        field.getType().getName()));
            }
        }
        for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
            Parameter[] parameters = constructor.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (isDirectDataAccessType(parameters[i].getType())) {
                    found.add(new Violation(controller.getName(), "constructor parameter #" + i,
                            parameters[i].getName(), parameters[i].getType().getName()));
                }
            }
        }
        return found;
    }

    /** The gate proper: every supplied controller, minus exemptions. */
    static List<Violation> violationsIn(Collection<Class<?>> controllers, Set<String> exempt) {
        List<Violation> found = new ArrayList<>();
        for (Class<?> controller : controllers) {
            if (exempt.contains(controller.getName())) {
                continue;
            }
            found.addAll(violationsIn(controller));
        }
        return found;
    }

    // ------------------------------------------------------------ enumeration

    /**
     * Every production top-level class, derived from the {@code src/main/java} tree. Deriving the
     * candidate set from SOURCE rather than from a component scan is what makes the enumeration
     * condition-agnostic: no {@code @Profile}, {@code @Conditional} or
     * {@code @ConditionalOnProperty} can hide a class from a directory walk.
     */
    private static List<String> mainTopLevelClassNames() {
        Path root = mainSourceRoot();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> root.relativize(p).toString())
                    .filter(rel -> !rel.endsWith("package-info.java") && !rel.endsWith("module-info.java"))
                    .map(rel -> rel.substring(0, rel.length() - ".java".length()).replace('/', '.'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return fail("Cannot walk %s: %s — enumeration failed, so no pass can be reported.", root, e);
        }
    }

    /**
     * Loads WITHOUT running static initialisers. Initialising several hundred production classes
     * outside a Spring context is both slow and a side-effect risk; the gate only needs each
     * class's annotations and declared member types, none of which require initialisation.
     */
    private static Class<?> loadWithoutInitialising(String fqcn) throws ClassNotFoundException {
        return Class.forName(fqcn, false, ControllerRepositoryInjectionGateTest.class.getClassLoader());
    }

    /** Names of every {@code @RestController} in production source, nested classes included. */
    private static Set<String> mainRestControllerNames() {
        Set<String> names = new TreeSet<>();
        List<String> unloadable = new ArrayList<>();
        for (String fqcn : mainTopLevelClassNames()) {
            try {
                collectRestControllers(loadWithoutInitialising(fqcn), names);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                unloadable.add(fqcn + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        assertThat(unloadable)
                .as("Production classes present in src/main/java that could not be loaded from the "
                        + "test classpath. Each one is a controller this gate cannot see, so this "
                        + "must be empty rather than tolerated — an unreadable class is an "
                        + "unchecked one, and 'found nothing' is never 'clean'.")
                .isEmpty();
        return names;
    }

    private static void collectRestControllers(Class<?> clazz, Set<String> into) {
        if (clazz.isAnnotationPresent(RestController.class)) {
            into.add(clazz.getName());
        }
        for (Class<?> nested : clazz.getDeclaredClasses()) {
            collectRestControllers(nested, into);
        }
    }

    private static Collection<Class<?>> mainRestControllers() {
        Collection<Class<?>> classes = new LinkedHashSet<>();
        for (String fqcn : mainRestControllerNames()) {
            try {
                classes.add(loadWithoutInitialising(fqcn));
            } catch (ClassNotFoundException e) {
                fail("Enumerated %s but it could not be loaded: %s", fqcn, e);
            }
        }
        return classes;
    }

    /** True when {@code fqcn}'s top-level enclosing class has a .java file under {@code root}. */
    private static boolean hasSourceUnder(Path root, String fqcn) {
        int nested = fqcn.indexOf('$');
        String topLevel = nested < 0 ? fqcn : fqcn.substring(0, nested);
        return Files.isRegularFile(root.resolve(topLevel.replace('.', '/') + ".java"));
    }

    /** Resolves whether the test runs from the module dir (Gradle) or the repo root (some IDEs). */
    private static Path sourceRoot(String sourceSet) {
        Path fromModule = Path.of("src", sourceSet, "java");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        Path fromRepoRoot = Path.of("core-java", "src", sourceSet, "java");
        if (Files.isDirectory(fromRepoRoot)) {
            return fromRepoRoot;
        }
        return fail("Cannot locate core-java src/%s/java from working dir %s — the gate cannot "
                + "distinguish production controllers from test stubs and must not report a pass.",
                sourceSet, Path.of("").toAbsolutePath());
    }

    private static Path mainSourceRoot() {
        return sourceRoot("main");
    }

    // ------------------------------------------------------------- instrument

    @Test
    @DisplayName("instrument - enumeration sees the real production controller surface")
    void theScanSeesTheRealControllerSurface() {
        List<String> sourceFiles = mainTopLevelClassNames();
        assertThat(sourceFiles)
                .as("Walked %d production source files. Below %d the source root has resolved to a "
                        + "real-but-wrong directory, and a controller count that happens to clear "
                        + "its own floor would still be measuring the wrong tree.",
                        sourceFiles.size(), MIN_EXPECTED_MAIN_SOURCE_FILES)
                .hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_MAIN_SOURCE_FILES);

        Set<String> scanned = mainRestControllerNames();
        assertThat(scanned)
                .as("Enumeration returned %d @RestController classes from src/main/java. 'Found "
                        + "nothing' is not 'clean': below %d this is broken enumeration (wrong "
                        + "source root, empty classpath, changed output layout) and every assertion "
                        + "below it would pass vacuously. Found: %s",
                        scanned.size(), MIN_EXPECTED_MAIN_CONTROLLERS, scanned)
                .hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_MAIN_CONTROLLERS);

        assertThat(scanned)
                .as("Seeded positive controls — each is a class some plausible implementation "
                        + "MISSES, so their presence proves this enumeration is strictly stronger "
                        + "than the filename glob AND than Spring's own component scanner, not "
                        + "merely different. See SCAN_POSITIVE_CONTROLS for which misses which. If "
                        + "a control was genuinely renamed, update the list — never delete the "
                        + "assertion.")
                .containsAll(SCAN_POSITIVE_CONTROLS);
    }

    @Test
    @DisplayName("instrument - enumeration is main-only and excludes this gate's own fixtures")
    void theScanIsRestrictedToMainAndExcludesTheGatesOwnFixtures() {
        Set<String> scanned = mainRestControllerNames();

        assertThat(scanned)
                .as("The deliberately-broken fixture must never enter enumeration. If it did, this "
                        + "gate would be permanently RED on a correct tree — the recorded "
                        + "'expected-0 that is actually 1 on the correct tree' trap — and the first "
                        + "person to hit it would delete the gate rather than the fixture.")
                .doesNotContain(ControllerInjectingRepositoryFixture.class.getName(),
                        CompliantControllerFixture.class.getName());

        // Enumeration walks src/main/java, so main-only holds by construction rather than by
        // filtering. This re-derives it independently from the test tree: several test classes DO
        // declare @RestController stubs (the ThrowingController beans in the exception-handler
        // tests), and none of them may be gated as production architecture. It is the assertion
        // that would catch someone repointing the walk at a broader root.
        Path testRoot = sourceRoot("test");
        for (String fqcn : scanned) {
            assertThat(hasSourceUnder(testRoot, fqcn))
                    .as("%s was enumerated as a production controller but its source is under "
                            + "src/test/java. Test-only controller stubs must not be gated.", fqcn)
                    .isFalse();
        }
    }

    // -------------------------------------------------- falsification (permanent)

    /**
     * The load-bearing falsification (#501). This runs on every CI run, so the gate is proven
     * CAPABLE of failing every time it is claimed to pass — rather than being observed only
     * passing over a tree that happens to be clean.
     *
     * <p>The fixture is fed to the SAME detector the production scan uses, alongside the real
     * production controllers, so this also proves the pipeline is not inert when handed the real
     * tree: it finds the planted violation and nothing else.
     */
    @Test
    @DisplayName("falsification - the gate goes RED on a planted @RestController that injects a repository")
    void theGateDetectsAPlantedRepositoryInjection() {
        Collection<Class<?>> withPlant = new LinkedHashSet<>(mainRestControllers());
        withPlant.add(ControllerInjectingRepositoryFixture.class);

        List<Violation> violations = violationsIn(withPlant, EXEMPT_CONTROLLERS);

        assertThat(violations)
                .as("The gate must FAIL when a repository-injecting @RestController is present. "
                        + "If this is empty the gate is incapable of failing and its green run over "
                        + "the production tree is not evidence of anything.")
                .isNotEmpty();
        assertThat(violations)
                .as("...and it must name the class AND the member, not just report a count (#501).")
                .anyMatch(v -> v.owner().equals(ControllerInjectingRepositoryFixture.class.getName())
                        && v.memberKind().equals("field")
                        && v.memberName().equals("deliveryRepository"))
                .anyMatch(v -> v.owner().equals(ControllerInjectingRepositoryFixture.class.getName())
                        && v.memberKind().startsWith("constructor parameter"));
        assertThat(violations)
                .as("Only the plant should be reported. Anything else here means the detector "
                        + "over-matches, and an over-matching gate is one that gets switched off.")
                .allMatch(v -> v.owner().equals(ControllerInjectingRepositoryFixture.class.getName()));
    }

    @Test
    @DisplayName("falsification - the detector does NOT fire on a structurally identical compliant controller")
    void theGateStaysGreenOnAControllerThatUsesAService() {
        assertThat(violationsIn(CompliantControllerFixture.class))
                .as("Same package, same @RestController, same constructor-injection style as the "
                        + "broken fixture — differing only in depending on a @Transactional service. "
                        + "If this reports a violation the detector matches on shape rather than on "
                        + "data access, and 'no violations in main' would mean nothing.")
                .isEmpty();
    }

    // ------------------------------------------------------------- the gate

    @Test
    @DisplayName("gate - no production @RestController injects a repository or an EntityManager")
    void noProductionRestControllerHoldsDirectDataAccess() {
        List<Violation> violations = violationsIn(mainRestControllers(), EXEMPT_CONTROLLERS);

        assertThat(violations)
                .as("A @RestController must reach the database through a @Transactional service "
                        + "(issue #501, instance #444). Injected directly, the read runs with no "
                        + "active transaction, TenantSetLocalAspect returns early without pinning "
                        + "app.current_tenant_id, and the FORCE-RLS policy matches no rows — so the "
                        + "endpoint answers 200 with an empty body and the breakage is invisible. "
                        + "Move the data access into a @Transactional service, or (rarely) add a "
                        + "commented entry to EXEMPT_CONTROLLERS. Offenders: %s", violations)
                .isEmpty();
    }

    // ------------------------------------------------------ exemption hygiene

    /**
     * Proves the exemption mechanism actually suppresses — on an input independently proven to be
     * a violation by {@link #theGateDetectsAPlantedRepositoryInjection()}. Without that pairing,
     * "the exemption produced no violations" would be indistinguishable from a detector that finds
     * nothing at all.
     */
    @Test
    @DisplayName("exemptions - naming a class in EXEMPT_CONTROLLERS suppresses exactly that class")
    void theExemptionMechanismSuppressesTheNamedClass() {
        Collection<Class<?>> justThePlant = List.of(ControllerInjectingRepositoryFixture.class);

        assertThat(violationsIn(justThePlant, Set.of()))
                .as("control: without an exemption this input IS a violation")
                .isNotEmpty();
        assertThat(violationsIn(justThePlant, Set.of(ControllerInjectingRepositoryFixture.class.getName())))
                .as("and with the exemption it is suppressed — so the mechanism works, and the "
                        + "empty EXEMPT_CONTROLLERS set below is an empty set rather than a "
                        + "mechanism that was never wired up")
                .isEmpty();
    }

    /**
     * Stale-exemption guard. EXEMPT_CONTROLLERS is empty today, so this assertion has nothing to
     * check and passes trivially — stated plainly rather than reported as proof. It becomes real
     * the moment anyone adds an entry, which is exactly when it is needed: an exemption for a class
     * that was since fixed, renamed or deleted would otherwise sit there suppressing whatever new
     * violation later took the same name.
     */
    @Test
    @DisplayName("exemptions - every entry still names a live, still-violating controller")
    void exemptionsMustBeLiveAndStillNecessary() {
        Set<String> scanned = mainRestControllerNames();
        for (String exempt : EXEMPT_CONTROLLERS) {
            assertThat(scanned)
                    .as("EXEMPT_CONTROLLERS names %s, which is not a production @RestController "
                            + "(renamed, deleted, or never one). Remove the exemption.", exempt)
                    .contains(exempt);
            try {
                assertThat(violationsIn(Class.forName(exempt)))
                        .as("EXEMPT_CONTROLLERS names %s, which no longer injects a repository. "
                                + "Remove the exemption so the class is gated again.", exempt)
                        .isNotEmpty();
            } catch (ClassNotFoundException e) {
                fail("EXEMPT_CONTROLLERS names %s, which cannot be loaded: %s", exempt, e);
            }
        }
    }
}
