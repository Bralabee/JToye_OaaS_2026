package uk.jtoye.core.exception;

import java.util.List;

/**
 * Phase 37-45 (D-22, Q7): a credential may carry only the six #206 client scopes. 400
 * {@code credential-scope-invalid}, naming the refused scopes and the allowed list so a client can correct
 * the request without reading prose.
 */
public class CredentialScopeInvalidException extends RuntimeException {

    private final List<String> invalidScopes;
    private final List<String> allowedScopes;

    public CredentialScopeInvalidException(List<String> invalidScopes, List<String> allowedScopes) {
        super("A credential may carry only these scopes: " + String.join(", ", allowedScopes) + ".");
        this.invalidScopes = List.copyOf(invalidScopes);
        this.allowedScopes = List.copyOf(allowedScopes);
    }

    public List<String> getInvalidScopes() { return invalidScopes; }
    public List<String> getAllowedScopes() { return allowedScopes; }
}
