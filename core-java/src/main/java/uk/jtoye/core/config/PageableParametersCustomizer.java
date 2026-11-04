package uk.jtoye.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * #879 / UXT-123 (P2-RAV-15): a paged list documents {@code page}, {@code size} and {@code sort}.
 *
 * <p>Spring binds a {@link Pageable} argument from three query parameters, but springdoc published it
 * as ONE required query parameter named {@code pageable} whose schema is an object, on the 14 list
 * operations that did not hide it, and published nothing at all on the rest (the controllers set
 * {@code @Parameter(hidden = true)}). A generated client could not page: the first group sends
 * {@code ?pageable=[object Object]}, the second does not know pagination exists.
 *
 * <p>Derived from the handler's own argument, so the defaults it prints are the ones
 * {@code @PageableDefault} applies and a new list endpoint is documented correctly for free. The
 * maximum size follows {@code spring.data.web.pageable.max-page-size}, the clamp the resolver enforces.
 */
@Component
public class PageableParametersCustomizer implements OperationCustomizer, OpenApiCustomizer {

    /** Spring Data's fallback page size for a Pageable argument that carries no {@code @PageableDefault}. */
    static final int FALLBACK_SIZE = 20;

    private static final String PAGEABLE_SCHEMA = "Pageable";

    @Value("${spring.data.web.pageable.max-page-size:2000}")
    private int maxPageSize = 2000;

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        for (MethodParameter mp : handlerMethod.getMethodParameters()) {
            if (Pageable.class.isAssignableFrom(mp.getParameterType())) {
                describe(operation, mp.getParameterAnnotation(PageableDefault.class));
            }
        }
        return operation;
    }

    private void describe(Operation operation, PageableDefault defaults) {
        List<Parameter> params = new ArrayList<>(operation.getParameters() == null ? List.of() : operation.getParameters());
        params.removeIf(p -> "pageable".equals(p.getName()) && "query".equals(p.getIn()));

        int size = defaults == null ? FALLBACK_SIZE : (defaults.size() != 10 ? defaults.size() : defaults.value());
        int page = defaults == null ? 0 : defaults.page();
        List<String> sortDefault = new ArrayList<>();
        if (defaults != null) {
            for (String property : defaults.sort()) {
                sortDefault.add(property + "," + defaults.direction().name().toLowerCase());
            }
        }

        addIfAbsent(params, new Parameter().in("query").name("page").required(false)
                .description("Zero-based page index")
                .schema(new IntegerSchema().format("int32").minimum(java.math.BigDecimal.ZERO)._default(page)));
        addIfAbsent(params, new Parameter().in("query").name("size").required(false)
                .description("Page size; larger values are clamped to " + maxPageSize)
                .schema(new IntegerSchema().format("int32").minimum(java.math.BigDecimal.ONE)
                        .maximum(java.math.BigDecimal.valueOf(maxPageSize))._default(size)));
        ArraySchema sortSchema = new ArraySchema().items(new StringSchema());
        if (!sortDefault.isEmpty()) {
            sortSchema.setDefault(sortDefault);
        }
        addIfAbsent(params, new Parameter().in("query").name("sort").required(false)
                .description("Sort order as property[,asc|desc]; repeat the parameter to sort by several properties")
                .style(Parameter.StyleEnum.FORM).explode(true)
                .schema(sortSchema));
        operation.setParameters(params);
    }

    private static void addIfAbsent(List<Parameter> params, Parameter p) {
        if (params.stream().noneMatch(e -> Objects.equals(e.getName(), p.getName()) && Objects.equals(e.getIn(), p.getIn()))) {
            params.add(p);
        }
    }

    /** Drops the now-unreferenced {@code Pageable} request schema springdoc registered while resolving the argument. */
    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null || openApi.getComponents() == null || openApi.getComponents().getSchemas() == null
                || !openApi.getComponents().getSchemas().containsKey(PAGEABLE_SCHEMA)) {
            return;
        }
        String json = io.swagger.v3.core.util.Json.pretty(openApi);
        if (!json.contains("\"#/components/schemas/" + PAGEABLE_SCHEMA + "\"")) {
            openApi.getComponents().getSchemas().remove(PAGEABLE_SCHEMA);
        }
    }
}
