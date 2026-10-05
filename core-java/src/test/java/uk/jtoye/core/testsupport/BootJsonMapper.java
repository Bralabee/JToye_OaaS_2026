package uk.jtoye.core.testsupport;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Boot 4's auto-configured Jackson-3 {@link JsonMapper}, for unit tests that must serialize or
 * parse exactly as the application does without starting the application (38-07).
 *
 * <p>It is built by {@link JacksonAutoConfiguration} alone in an {@link ApplicationContextRunner}.
 * That is the bean the application injects: {@code core-java/src/main} declares no
 * {@code JsonMapperBuilderCustomizer}, Jackson module bean or {@code spring.jackson.*} key (the
 * 38-05 decision is jackson3-defaults), so nothing else shapes it. Boot's
 * {@code JsonProblemDetailsConfiguration} registers the {@code ProblemDetail} mixin here, which is
 * what flattens extension members to the top level of a problem document.
 *
 * <p>Where a test needs the bean of the FULL context (every module registered by every
 * auto-configuration), {@code Jackson3WireContractTest} is the place; this helper is for the
 * fast unit slice.
 */
public final class BootJsonMapper {

    private static final JsonMapper INSTANCE = build();

    private BootJsonMapper() {
    }

    /** The shared instance. A {@link JsonMapper} is immutable and thread-safe. */
    public static JsonMapper get() {
        return INSTANCE;
    }

    private static JsonMapper build() {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    failure.set(context.getStartupFailure());
                    if (failure.get() == null) {
                        mapper.set(context.getBean(JsonMapper.class));
                    }
                });
        if (mapper.get() == null) {
            throw new IllegalStateException("JacksonAutoConfiguration produced no JsonMapper", failure.get());
        }
        return mapper.get();
    }
}
