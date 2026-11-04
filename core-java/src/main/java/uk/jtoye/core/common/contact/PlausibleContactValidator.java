package uk.jtoye.core.common.contact;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PlausibleContactValidator implements ConstraintValidator<PlausibleContact, String> {

    private PlausibleContact.Kind kind;

    @Override
    public void initialize(PlausibleContact annotation) {
        this.kind = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (kind) {
            case NAME -> ContactPlausibility.isPlausibleName(value);
            case PHONE -> ContactPlausibility.isPlausiblePhone(value);
            case EMAIL -> ContactPlausibility.isPlausibleEmail(value);
        };
    }
}
