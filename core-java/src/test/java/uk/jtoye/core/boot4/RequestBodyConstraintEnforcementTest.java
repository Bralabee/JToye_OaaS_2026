package uk.jtoye.core.boot4;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 38 (BOOT4-13): the "enforced today" oracle for every request field the OpenAPI snapshot
 * may newly mark {@code required} after the Boot 4 / springdoc 3 move.
 *
 * <p>The inventory is STRUCTURAL, not a text search: every {@link HandlerMethod} the live
 * {@link RequestMappingHandlerMapping} serves, every {@code @RequestBody} parameter on it, and the
 * Bean Validation metadata ({@link Validator#getConstraintsForClass}) of that parameter's type. A
 * {@code @NotNull}/{@code @NotBlank}/{@code @NotEmpty} property becomes one row; a cascaded
 * property ({@code @Valid}, or {@code List<@Valid X>}) is recursed into and its element type's rows
 * are emitted under the nested type's simple name, which is how springdoc names nested schemas.
 *
 * <p>Three assertions make each row a proof rather than a listing:
 * <ol>
 *   <li>the inventory is non-empty (an empty walk is a broken walk, not a clean tree);</li>
 *   <li>the parameter carries {@code @Valid} or {@code @Validated} - a constraint on a body that is
 *       never validated is silently unenforced, and the failure names it;</li>
 *   <li>{@link Validator#validateValue} with {@code null} yields at least one violation under the
 *       default group, i.e. what {@code @Valid} actually runs.</li>
 * </ol>
 * One real HTTP request then proves the mechanism end to end on the public guest-order path.
 *
 * <p>The sorted rows are written to {@code build-local/boot4/request-body-constraints.tsv}; the
 * Boot 3.5.16 run is committed as
 * {@code .planning/phases/38-spring-boot-4-1-migration/evidence/38-02-request-body-constraints-boot35.tsv}
 * and plan 38-14 checks every newly-required OpenAPI field against it. Runs untagged on H2 so it
 * guards every later plan from the fast {@code test} task. The request JSON is a literal so the
 * class compiles on both Jackson lines.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestBodyConstraintEnforcementTest {

    /** Controllers whose bean type lives under this package are inventoried. */
    static final String PACKAGE_ROOT = "uk.jtoye.core";

    static final Path INVENTORY_OUT = Path.of("build-local", "boot4", "request-body-constraints.tsv");
    static final Path HTTP_PROOF_OUT = Path.of("build-local", "boot4", "guest-order-missing-email-400.txt");

    private static final Set<Class<? extends Annotation>> REQUIRED_CONSTRAINTS =
            Set.of(NotNull.class, NotBlank.class, NotEmpty.class);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private Validator validator;

    @Autowired
    private MockMvc mockMvc;

    /** One inventoried row, with the facts the assertions need. */
    record Row(String endpoint, Class<?> dtoType, String property, String constraint, String handler,
               boolean bodyValidated) {
        String tsv() {
            return endpoint + "\t" + dtoType.getSimpleName() + "\t" + property + "\t" + constraint;
        }
    }

    @Test
    @DisplayName("every required request-body property is behind @Valid and rejects null")
    void everyRequiredRequestBodyPropertyIsEnforced() throws Exception {
        List<Row> rows = inventory(PACKAGE_ROOT);

        assertThat(rows)
                .as("request-body constraint inventory under %s is empty: the walk found no "
                        + "@RequestBody with a NotNull/NotBlank/NotEmpty property, so it proves nothing",
                        PACKAGE_ROOT)
                .isNotEmpty();

        List<String> unvalidated = rows.stream()
                .filter(r -> !r.bodyValidated())
                .map(r -> r.handler() + " -> " + r.tsv())
                .distinct().sorted().toList();
        assertThat(unvalidated)
                .as("@RequestBody parameters with required constraints but no @Valid/@Validated "
                        + "(their constraints are never run)")
                .isEmpty();

        List<String> notRejected = new ArrayList<>();
        for (Row r : rows) {
            if (validator.validateValue(r.dtoType(), r.property(), null).isEmpty()) {
                notRejected.add(r.tsv());
            }
        }
        assertThat(notRejected)
                .as("rows whose property accepts null under the default validation group")
                .isEmpty();

        Set<String> sorted = rows.stream().map(Row::tsv).collect(Collectors.toCollection(TreeSet::new));
        Files.createDirectories(INVENTORY_OUT.getParent());
        Files.writeString(INVENTORY_OUT, String.join("\n", sorted) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("POST /public/shops/{slug}/orders without customerEmail is a 400 validation problem naming it")
    void guestOrderWithoutCustomerEmailIsRejected() throws Exception {
        // Valid in every respect except that customerEmail is absent.
        String body = """
                {"customerName":"Ada Test","customerPhone":"07700900000",
                 "fulfilmentType":"COLLECTION",
                 "items":[{"productId":"00000000-0000-0000-0000-000000000001","quantity":1}]}
                """;

        MvcResult result = mockMvc.perform(post("/public/shops/any-slug/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                // Recorded BEFORE any expectation, so a non-400 answer (a filter or interceptor
                // replying ahead of validation) is captured verbatim rather than lost.
                .andDo(r -> {
                    Files.createDirectories(HTTP_PROOF_OUT.getParent());
                    Files.writeString(HTTP_PROOF_OUT,
                            "status=" + r.getResponse().getStatus() + "\n"
                                    + "content-type=" + r.getResponse().getContentType() + "\n"
                                    + "body=" + r.getResponse().getContentAsString(StandardCharsets.UTF_8) + "\n",
                            StandardCharsets.UTF_8);
                })
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/validation"))
                .andExpect(jsonPath("$.errors.customerEmail").exists())
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("customerEmail");
    }

    // ---------------------------------------------------------------------------------------

    List<Row> inventory(String packageRoot) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = e.getValue();
            if (!hm.getBeanType().getName().startsWith(packageRoot + ".")) {
                continue;
            }
            String endpoint = endpointOf(e.getKey());
            for (MethodParameter p : hm.getMethodParameters()) {
                if (!p.hasParameterAnnotation(RequestBody.class)) {
                    continue;
                }
                boolean validated = p.hasParameterAnnotation(Valid.class)
                        || p.hasParameterAnnotation(Validated.class);
                String handler = hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
                Class<?> bodyType = elementType(p.getGenericParameterType(), p.getParameterType());
                walk(bodyType, endpoint, handler, validated, rows, new HashSet<>());
            }
        }
        return rows;
    }

    private void walk(Class<?> type, String endpoint, String handler, boolean validated,
                      List<Row> rows, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) {
            return;
        }
        BeanDescriptor bean = validator.getConstraintsForClass(type);
        for (PropertyDescriptor pd : bean.getConstrainedProperties()) {
            for (ConstraintDescriptor<?> cd : pd.getConstraintDescriptors()) {
                Class<? extends Annotation> annotationType = cd.getAnnotation().annotationType();
                if (REQUIRED_CONSTRAINTS.contains(annotationType)) {
                    rows.add(new Row(endpoint, type, pd.getPropertyName(), annotationType.getSimpleName(),
                            handler, validated));
                }
            }
            boolean cascaded = pd.isCascaded() || pd.getConstrainedContainerElementTypes().stream()
                    .anyMatch(ContainerElementTypeDescriptor::isCascaded);
            if (cascaded) {
                walk(cascadeTarget(type, pd), endpoint, handler, validated, rows, seen);
            }
        }
    }

    private static String endpointOf(RequestMappingInfo info) {
        String methods = info.getMethodsCondition().getMethods().stream()
                .map(Enum::name).sorted().collect(Collectors.joining(","));
        String patterns = info.getPatternValues().stream().sorted().collect(Collectors.joining(","));
        return (methods.isEmpty() ? "ANY" : methods) + " " + patterns;
    }

    /** The type a cascaded property validates: the collection/map element, or the declared type. */
    private static Class<?> cascadeTarget(Class<?> owner, PropertyDescriptor pd) {
        Type generic = declaredGenericType(owner, pd.getPropertyName());
        return elementType(generic, pd.getElementClass());
    }

    private static Type declaredGenericType(Class<?> owner, String property) {
        if (owner.isRecord()) {
            for (RecordComponent rc : owner.getRecordComponents()) {
                if (rc.getName().equals(property)) {
                    return rc.getGenericType();
                }
            }
        }
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(property)) {
                    return f.getGenericType();
                }
            }
        }
        String cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (String getter : List.of("get" + cap, "is" + cap)) {
            try {
                Method m = owner.getMethod(getter);
                return m.getGenericReturnType();
            } catch (NoSuchMethodException ignored) {
                // try the next accessor form
            }
        }
        throw new IllegalStateException("cannot resolve declared type of " + owner.getName() + "." + property);
    }

    private static Class<?> elementType(Type generic, Class<?> raw) {
        if (raw.isArray()) {
            return raw.getComponentType();
        }
        if (generic instanceof GenericArrayType gat) {
            return rawClass(gat.getGenericComponentType());
        }
        if (generic instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw) && args.length == 1) {
                return rawClass(args[0]);
            }
            if (Map.class.isAssignableFrom(raw) && args.length == 2) {
                return rawClass(args[1]);
            }
            if (java.util.Optional.class.equals(raw) && args.length == 1) {
                return rawClass(args[0]);
            }
        }
        return raw;
    }

    private static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType pt) {
            return rawClass(pt.getRawType());
        }
        throw new IllegalStateException("unsupported element type " + t);
    }
}
