package io.github.hyperprivacy.core.rule.catalog

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text

/**
 * Makes a protected third-party app's native code follow the domain blocks its Java layer already
 * enforces ([RulePayload.NativeResolver]); off until the user enables it for an app, and only
 * installed in a process where it is on as the app starts.
 */
internal fun nativeResolverRule(): Rule = Rule(
    id = NATIVE_RESOLVER_RULE_ID,
    packages = setOf(Catalog.PROTECTED_APPS),
    title = Text("拦截原生网络请求", "Block native network calls"),
    summary = Text("原生代码也遵守域名拦截", "Native code follows domain blocks"),
    payload = RulePayload.NativeResolver,
    category = Category.ANALYTICS,
    evidence = "docs/privacy-surface.md (per-app protection): SDKs in NDK libraries and cross-platform " +
        "frameworks resolve host names from native code; this applies the app's active domain blocks to " +
        "common native name lookups, so native libraries follow the same domain policy",
    defaultOn = false,
    preventive = true,
)

/** Id of [nativeResolverRule]; persisted in preferences. */
internal const val NATIVE_RESOLVER_RULE_ID: String = "native.dns"
