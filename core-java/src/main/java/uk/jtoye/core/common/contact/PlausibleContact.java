package uk.jtoye.core.common.contact;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated contact field must be a plausible name, phone or email (#841), judged by
 * {@link ContactPlausibility}. {@code null} and blank pass: presence is {@code @NotBlank}'s job (or, for the
 * vendor order, the at-least-one-contact rule), so one rule never answers with two messages.
 */
@Documented
@Constraint(validatedBy = PlausibleContactValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlausibleContact {

    Kind value();

    String message() default "Not a plausible contact detail";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    enum Kind { NAME, PHONE, EMAIL }
}
