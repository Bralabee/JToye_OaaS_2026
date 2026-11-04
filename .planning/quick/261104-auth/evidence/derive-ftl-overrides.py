"""Derive the three jtoye FTL overrides from the Keycloak 26.8.1 base/login templates.

Every edit is an exact-string replacement that must match exactly once, so a base template that
differs from what this was written against fails loudly instead of silently producing a stale copy.
"""
import sys

BASE = "/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/08e7e4e3-744d-4528-ab24-88c465b1f21e/scratchpad/kc/jar/theme/base/login/"
OUT = "/home/sanmi/IdeaProjects/JToye_OaaS_2026/.claude/worktrees/agent-a33aca6a2fcac6aee/infra/keycloak/themes/jtoye/login/"

HEADER = """<#--
  J'TOYE OVERRIDE of Keycloak {ver} theme/base/login/{name} (issue #848, UXT-079, SW-MRC-12, SW-SAM-10).
  JTOYE-BASE-KEYCLOAK: {ver}
  Everything outside the blocks marked JTOYE below is the stock file byte for byte. When Keycloak is
  upgraded, re-derive this file from the new base (recipe: infra/keycloak/README.md, "FTL overrides")
  and re-apply the marked blocks; scripts/check-keycloak-theme-ftl.sh refuses to pass until the marker
  above equals the pinned image version.
-->
"""
VER = "26.8.1"


def sub(text, old, new, label):
    n = text.count(old)
    if n != 1:
        sys.exit(f"FATAL: {label}: expected exactly 1 match, found {n}")
    return text.replace(old, new, 1)


def load(name):
    return open(BASE + name, encoding="utf-8").read()


def write(name, text):
    open(OUT + name, "w", encoding="utf-8").write(HEADER.format(ver=VER, name=name) + text)


# ---------------------------------------------------------------- template.ftl
t = load("template.ftl")
t = sub(
    t,
    "    <title>${title!}</title>\n",
    """    <#-- JTOYE: the title names the PAGE ("Register - J'Toye"), not the stock "Sign in to J'Toye" on every page.
         The heading is the page's own h1 section, rendered once more here with its tags stripped. -->
    <#assign jtPageHeading><#nested "header"></#assign>
    <#assign jtPageHeadingText = jtPageHeading?markup_string?replace('<[^>]*>', '', 'r')?replace('\\\\s+', ' ', 'r')?trim>
    <title><#if jtPageHeadingText?has_content><#noautoesc>${jtPageHeadingText}</#noautoesc> - ${(realm.displayName!realm.name)}<#else>${title!}</#if></title>
""",
    "title",
)
t = sub(
    t,
    '<button tabindex="1" id="kc-current-locale-link"',
    '<button id="kc-current-locale-link"',
    "locale tabindex",
)
t = sub(
    t,
    '    <div id="kc-header" class="${properties.kcHeaderClass!}">\n        <div id="kc-header-wrapper"\n             class="${properties.kcHeaderWrapperClass!}">${kcSanitize(msg("loginTitleHtml",(realm.displayNameHtml!\'\')))?no_esc}</div>\n    </div>\n    <div class="${properties.kcFormCardClass!}">\n',
    '    <#-- JTOYE: banner landmark for the wordmark, main landmark for the card (axe landmark-one-main / region). -->\n    <header id="kc-header" class="${properties.kcHeaderClass!}">\n        <div id="kc-header-wrapper"\n             class="${properties.kcHeaderWrapperClass!}">${kcSanitize(msg("loginTitleHtml",(realm.displayNameHtml!\'\')))?no_esc}</div>\n    </header>\n    <main id="kc-main">\n    <div class="${properties.kcFormCardClass!}">\n',
    "header/main open",
)
t = sub(
    t,
    "      <@loginFooter.content/>\n    </div>\n  </div>\n</body>",
    "      <@loginFooter.content/>\n    </div>\n    </main>\n  </div>\n</body>",
    "main close",
)
write("template.ftl", t)

# ---------------------------------------------------------------- login.ftl
l = load("login.ftl")
import re

before = len(re.findall(r'tabindex="[1-9]', l))
l2 = re.sub(r' tabindex="[1-9]"', "", l)
# the anchor in the registration link spans two lines: `<a tabindex="8"\n  href=`
assert not re.search(r'tabindex="[1-9]', l2), "positive tabindex left in login.ftl"
assert before == 8, f"login.ftl had {before} positive tabindex attributes, expected 8 (base changed?)"
write("login.ftl", l2)

# ---------------------------------------------------------------- register.ftl
r = load("register.ftl")
r = sub(
    r,
    """                                    <input type="password" id="password" class="${properties.kcInputClass!}" name="password"
                                           autocomplete="new-password"
                                           aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>"
                                    />""",
    """                                    <#-- JTOYE: the password rules are on the page BEFORE a failed submit and tied to the field. -->
                                    <input type="password" id="password" class="${properties.kcInputClass!}" name="password"
                                           autocomplete="new-password" aria-required="true"
                                           aria-describedby="kc-password-policy<#if messagesPerField.existsError('password')> input-error-password</#if>"
                                           aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>"
                                    />""",
    "password input",
)
r = sub(
    r,
    """                                <#if messagesPerField.existsError('password')>
                                    <span id="input-error-password" """,
    """                                <span id="kc-password-policy" class="${properties.kcInputHelperTextClass!} kc-password-policy">${msg("passwordPolicyHint")}</span>

                                <#if messagesPerField.existsError('password')>
                                    <span id="input-error-password" """,
    "policy hint",
)
r = sub(
    r,
    """                                           name="password-confirm" autocomplete="new-password"
                                           aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>"
""",
    """                                           name="password-confirm" autocomplete="new-password" aria-required="true"
                                           <#if messagesPerField.existsError('password-confirm')>aria-describedby="input-error-password-confirm"</#if>
                                           aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>"
""",
    "confirm input",
)
r = sub(
    r,
    "            <@registerCommons.termsAcceptance/>\n",
    """            <@registerCommons.termsAcceptance/>

            <#-- JTOYE (SW-SAM-10): the privacy notice is linked where personal data is collected. The notice lives
                 on the app, so its origin is the storefront client's own baseUrl (set per environment by the realm
                 template); with no usable baseUrl the line is omitted rather than pointing somewhere wrong. -->
            <#assign jtAppOrigin = ((client.baseUrl)!"")?replace('^(https?://[^/]+).*$', '$1', 'r')>
            <#if jtAppOrigin?matches('^https?://[^/]+$')>
                <p id="kc-privacy-notice" class="kc-privacy-notice">${kcSanitize(msg("registerPrivacyNotice", jtAppOrigin + "/legal/privacy"))?no_esc}</p>
            </#if>
""",
    "privacy",
)
write("register.ftl", r)
print("ok")
