package uk.jtoye.core.security.access;

import java.util.function.Supplier;

/**
 * The explicit declaration that the current thread is executing internal SYSTEM work
 * (#283). Internal trust is something a caller <strong>asserts</strong>, never something
 * the absence of an identity implies.
 *
 * <p><strong>What this replaces.</strong> Until Phase 28,
 * {@code ShopAccessService.isInternalCaller()} returned {@code true} whenever the thread
 * carried no {@code Authentication} — so "I have no identity" meant "I am trusted". The
 * rule was correct only because no gated service was reachable from a background path, a
 * property nothing enforced and any new call could end. It is now inverted: an absent
 * principal with no declaration is DENIED, and a background entry point that legitimately
 * acts as the system says so by wrapping its body in {@link #asSystem(Runnable)}.
 *
 * <p>This mirrors the declaration-over-inference move the same gate already made for
 * machine clients ({@code ShopAccessService.isDeclaredMachineClient}, CR-03 / D-04): trust
 * is granted ONLY by an explicit declaration and never inferred from an identity the code
 * could not parse.
 *
 * <h2>Marker shape: a {@code ThreadLocal} flag, NOT a sentinel {@code Authentication}</h2>
 *
 * Both shapes satisfy #283. The trade is <em>how far the declaration reaches</em>, and the
 * narrower one was chosen deliberately:
 *
 * <ul>
 *   <li><strong>Chosen — {@code ThreadLocal} flag.</strong> The declaration is visible to
 *       exactly one consumer, {@code ShopAccessService.isInternalCaller()}. It grants
 *       nothing anywhere else: a {@code @PreAuthorize} method, a {@code SecurityContext}
 *       -reading filter, or any future authorization component still sees an
 *       unauthenticated thread and still refuses it. A background path that needs to pass
 *       one of those must be handled explicitly, which is the outcome this issue wants.</li>
 *   <li><strong>Rejected — a sentinel {@code Authentication} carrying {@code ROLE_SYSTEM}.</strong>
 *       It would make the declaration visible to the rest of Spring Security, which sounds
 *       like an advantage and is actually the cost: installing an authenticated principal
 *       into the {@code SecurityContext} satisfies <em>every</em> other check on that
 *       thread at once. #283 asks for one narrow bypass to become explicit; a sentinel
 *       would widen it into a general-purpose authenticated identity, and the next reader
 *       would have to prove a negative about everything it now passes.</li>
 * </ul>
 *
 * <p><strong>The marker is an AUTHORISATION declaration, not a tenancy escape.</strong>
 * A system caller is still tenant-scoped by RLS exactly as every other caller is: the
 * {@code app.current_tenant_id} GUC is pinned from {@link uk.jtoye.core.security.TenantContext}
 * by {@code TenantSetLocalAspect}, and {@code FORCE ROW LEVEL SECURITY} filters every read
 * and write to the pinned tenant. Declaring system work says "this thread may pass the
 * shop-scope gate"; it says nothing whatsoever about which tenant's rows it can see, and it
 * cannot be used to reach another tenant's data.
 *
 * <h2>Lifecycle: the prior value is RESTORED, never blanket-cleared</h2>
 *
 * {@link #asSystem} saves the incoming value, sets the marker, and restores the saved value
 * in a {@code finally}. Two failure modes make that specific shape load-bearing:
 * <ul>
 *   <li>a NESTED {@code asSystem} inside an outer one must not drop the outer declaration
 *       when the inner body returns (an unconditional clear would);</li>
 *   <li>a POOLED thread must not keep a stale marker after its scope ends — that is a
 *       bypass outliving the work that declared it, and it would be handed to whatever ran
 *       next on that thread. On the outermost exit the entry is {@link ThreadLocal#remove()}d
 *       rather than set to {@code false}, so the thread carries no entry at all.</li>
 * </ul>
 */
public final class SystemPrincipal {

    /**
     * Absent (the common case) or {@code TRUE} while a declared system scope is on the
     * stack. Never stored as {@code FALSE}: the outermost {@link #asSystem} removes the
     * entry entirely so a pooled thread is left exactly as it was found.
     */
    private static final ThreadLocal<Boolean> SYSTEM = new ThreadLocal<>();

    private SystemPrincipal() {
    }

    /**
     * True when the current thread is inside a declared {@link #asSystem} scope. The single
     * question {@code ShopAccessService.isInternalCaller()} asks; an undeclared thread —
     * with or without an {@code Authentication} — answers {@code false} and is denied.
     */
    public static boolean isSystem() {
        return Boolean.TRUE.equals(SYSTEM.get());
    }

    /**
     * Run {@code body} as declared internal system work.
     *
     * <p>The overload pair is not ambiguous, and the rule is worth stating because it looks
     * as though it should be: for an expression lambda whose body produces a value, JLS
     * 15.12.2.5 makes {@link Supplier} strictly more specific than {@code Runnable} (a
     * non-void function type beats a void one), so {@code asSystem(() -> svc.thatReturns())}
     * resolves to the {@link #asSystem(Supplier)} overload. A {@code void} call or a block
     * lambda leaves {@code Supplier} inapplicable and selects this one. Verified by
     * compilation, not assumed.
     */
    public static void asSystem(Runnable body) {
        asSystem(() -> {
            body.run();
            return null;
        });
    }

    /**
     * Run {@code body} as declared internal system work and return its result. Restores the
     * PRIOR declaration in a {@code finally} — see the class javadoc for why restoring
     * rather than clearing is the load-bearing part.
     */
    public static <T> T asSystem(Supplier<T> body) {
        boolean previouslyDeclared = isSystem();
        SYSTEM.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (previouslyDeclared) {
                SYSTEM.set(Boolean.TRUE);   // nested scope: leave the OUTER declaration intact
            } else {
                SYSTEM.remove();            // outermost scope: leave the pooled thread clean
            }
        }
    }
}
