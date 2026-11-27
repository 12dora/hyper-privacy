package io.github.hyperprivacy.core.rule

import io.github.hyperprivacy.core.match.UrlQuery

/**
 * Which kind of privacy reporting a rule blocks. The catalog only contains reporting channels:
 * anything a vendor service needs to work (store, push, sync, security scan …) is out of scope.
 */
public enum class Category {
    /** Event and behaviour tracking. */
    ANALYTICS,

    /** Log, crash and performance upload. */
    DIAGNOSTICS,

    /** Ad attribution and ad reporting. */
    ADS,

    /** Device identifier collection. */
    DEVICE_ID,

    /** Installed-app list, app activity and usage-time uploads. */
    APP_LIST,

    /** Location, Wi-Fi / cell environment and other personal data uploads. */
    PERSONAL_DATA,
}

/** The enforcement mechanism behind a rule. */
public enum class RuleKind {
    /** Whole-app network cut via the kernel firewall, enforced from system_server. */
    FIREWALL,

    /** Host-suffix block at the DNS and TLS sinks inside the target process. */
    DOMAIN,

    /** No-op a named public SDK method inside the target process. */
    METHOD,

    /** Host + path-prefix block at the HTTP client layer inside the target process. */
    URL,

    /** Hide device identifiers served by the target process from third-party callers. */
    IDENTIFIER,
}

/** Where a device identifier is served from; each source has one enforcement point. */
public enum class IdentifierSource {
    /** OAID / VAID / AAID / UDID served by `content://com.miui.idprovider` (hosted by the security center). */
    MIUI_ID_PROVIDER,

    /**
     * Identifier- and app-list-bearing vendor keys in `Settings.Global` / `Secure` / `System`,
     * served by SettingsProvider inside system_server (target: [Catalog.SYSTEM_SCOPE]). Third-party
     * callers see them as absent (`core.system.SettingsDenyList`), and the user-set device names as
     * the unrenamed model name (`core.system.SettingsMaskList`).
     */
    SETTINGS_KEYS,

    /**
     * `MediaDrm` property `deviceUniqueId` (Widevine), read by any app in its own process. Not hidden
     * but replaced per app: each package gets a stable value derived from the real one and its
     * package name, so the id can no longer link one app to another. Only for protected
     * third-party apps ([Catalog.protectedAppRules]), never for vendor packages.
     */
    MEDIA_DRM_ID,

    /**
     * Hardware characteristics readable without a permission and hashed into device fingerprints
     * (sensor resolution, power and version, the file system id, the battery cycle count), made
     * different per app; see `core.fingerprint.HardwareTraits`. Only for protected third-party apps
     * ([Catalog.protectedAppRules]), never for vendor packages, and off unless the user enables it.
     */
    HARDWARE_TRAITS,

    /**
     * Permission-free sensor and battery readings that leak more than an app needs (motion and
     * magnetic calibration, fine pressure and light changes, battery current and voltage), made
     * coarser per app; see `core.sensor`. Only for protected third-party apps
     * ([Catalog.protectedAppRules]), never for vendor packages, and off unless the user enables it.
     */
    SENSOR_DATA,
}

/**
 * A URL block entry: [host] matches exactly or as a dot-suffix (same rules as DOMAIN), and the
 * request path must start with [pathPrefix]. Used where one host serves both a vendor service
 * and a reporting endpoint, so blocking the whole host would break the service. [query], if set,
 * is one `key=value` parameter the request's query string must also contain, for endpoints that
 * multiplex services on one path by a query parameter (see `match.UrlQuery`).
 */
public data class UrlPrefix(val host: String, val pathPrefix: String, val query: String? = null) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(pathPrefix.startsWith('/') && pathPrefix.length > 1) {
            "pathPrefix must be a non-root path: $pathPrefix"
        }
        require(query == null || UrlQuery.isParam(query)) { "query must be one key=value parameter: $query" }
    }
}

/** User-facing text in the two shipped languages. */
public data class Text(val zh: String, val en: String) {
    init {
        require(zh.isNotBlank() && en.isNotBlank()) { "Text needs both zh and en" }
    }

    /** Picks [zh] for Chinese locales, [en] otherwise. */
    public fun resolve(languageTag: String): String = if (languageTag.startsWith("zh")) zh else en
}

/** Kind-specific data of a rule. The rule's [RuleKind] is derived from it, so they cannot disagree. */
public sealed interface RulePayload {
    public val kind: RuleKind

    /** Cut all traffic of every target package. Targets must own a non-shared app uid. */
    public data object Firewall : RulePayload {
        override val kind: RuleKind get() = RuleKind.FIREWALL
    }

    /** Block hosts equal to or under any of [suffixes]. */
    public data class Domains(val suffixes: List<String>) : RulePayload {
        override val kind: RuleKind get() = RuleKind.DOMAIN

        init {
            require(suffixes.isNotEmpty()) { "Domains payload needs at least one suffix" }
        }
    }

    /**
     * Replace methods of [className] matching [method] with a return-type default.
     *
     * Methods returning a reference type are skipped unless [allowNullReturn] is set, because
     * callers frequently dereference such results without a null check.
     *
     * [params] and [returns], when set, pin the exact signature (binary names as `Class.getName`
     * spells them, e.g. `int`, `[B`, `android.content.Context`): a method of another shape is left
     * alone, so an app update that renames or reshapes the target only turns the rule off. Both are
     * required for a null return and for an obfuscated name ([MethodPattern.isObfuscated]), since
     * R8 hands such names to unrelated methods from one release to the next; see [MethodShape].
     *
     * [nativeLibrary] names the library (`System.loadLibrary` name, e.g. `mievent`) that binds the
     * class's native methods. A matching native method is hooked only once that library has loaded
     * (and only while the rule is on as the app starts); without it, native methods are left alone.
     */
    public data class Method(
        val className: String,
        val method: MethodPattern,
        val allowNullReturn: Boolean = false,
        val params: List<String>? = null,
        val returns: String? = null,
        val nativeLibrary: String? = null,
    ) : RulePayload {
        override val kind: RuleKind get() = RuleKind.METHOD

        /** The signature the install-time check enforces. */
        val shape: MethodShape get() = MethodShape(params, returns)

        init {
            require(className.isNotBlank()) { "className must not be blank" }
            require(!(allowNullReturn || method.isObfuscated) || (params != null && returns != null)) {
                "Method $className#$method returns null or has an obfuscated name, so it must pin params and returns"
            }
            require(nativeLibrary == null || LIBRARY_NAME.matches(nativeLibrary)) {
                "Method $className#$method has an invalid native library name: $nativeLibrary"
            }
            require(!allowNullReturn || returns == null || returns !in MethodShape.PRIMITIVE_NAMES) {
                "Method $className#$method cannot return null from primitive $returns"
            }
        }

        private companion object {
            /** A bare `System.loadLibrary` name: no `lib` prefix, `.so` suffix or path. */
            val LIBRARY_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.+-]*")
        }
    }

    /** Fail requests whose URL matches any of [prefixes] (HTTP client layer). */
    public data class Urls(val prefixes: List<UrlPrefix>) : RulePayload {
        override val kind: RuleKind get() = RuleKind.URL

        init {
            require(prefixes.isNotEmpty()) { "Urls payload needs at least one prefix" }
        }
    }

    /**
     * The hosts and URL prefixes of every SDK in the tracker database that generic protection
     * blocks by default (not identification, not push), for protected third-party apps whose scan
     * found nothing, e.g. packed apps. Resolved only where the rule is enforced
     * ([io.github.hyperprivacy.core.tracker.TrackerRules.rulesFor] turns it into DOMAIN and URL
     * rules with the same id), so building the catalog never parses the tracker table.
     */
    public data object KnownTrackers : RulePayload {
        override val kind: RuleKind get() = RuleKind.DOMAIN
    }

    /**
     * Apply the host-level block list the process already enforces (its enabled DOMAIN rules, with
     * keep hosts) to host name lookups from the app's native code too. Carries no hosts of its own,
     * so it never adds to what the network sinks block; for protected third-party apps only
     * ([Catalog.protectedAppRules]) and off unless the user enables it.
     */
    public data object NativeResolver : RulePayload {
        override val kind: RuleKind get() = RuleKind.DOMAIN
    }

    /** Hide identifiers served by [source] from callers that are not system apps (zeroed or absent). */
    public data class Identifiers(val source: IdentifierSource) : RulePayload {
        override val kind: RuleKind get() = RuleKind.IDENTIFIER
    }
}

/**
 * A single user-toggleable protection.
 *
 * @property id stable identifier; persisted in preferences, so never rename a shipped id.
 * @property packages target package names the rule applies to; [Catalog.SYSTEM_SCOPE] stands for
 *   system_server itself (rules enforced there on behalf of every app, e.g. Settings keys), and
 *   exactly `{`[Catalog.PROTECTED_APPS]`}` for the user's protected third-party apps
 *   ([Catalog.protectedAppRules]).
 * @property evidence where the reporting channel was observed, e.g.
 *   `traffic: com.miui.analytics → tracking.miui.com` or `dex: Lcom/xiaomi/onetrack/OneTrack`.
 * @property impact null when blocking cannot affect any vendor service. Non-null for a channel
 *   that shares its endpoint with a service (e.g. cloud virus scan uploads app fingerprints):
 *   such a rule describes the side effect here and must ship disabled, unless it is [highRisk].
 * @property highRisk data so sensitive that the rule ships enabled even though [impact] describes a
 *   side effect. The side effect is still shown to the user. A high-risk rule must therefore be
 *   [defaultOn] and carry an [impact].
 * @property preventive the rule targets every hooked package as a precaution rather than because
 *   each target was seen using the channel, so a package where it never blocks anything is the
 *   expected case and is never reported as idle.
 */
public data class Rule(
    val id: String,
    val packages: Set<String>,
    val title: Text,
    val summary: Text,
    val payload: RulePayload,
    val category: Category,
    val evidence: String,
    val defaultOn: Boolean = true,
    val impact: Text? = null,
    val highRisk: Boolean = false,
    val preventive: Boolean = false,
) {
    val kind: RuleKind get() = payload.kind

    /** Targets [Catalog.PROTECTED_APPS] only: the user's protected third-party apps, resolved at runtime. */
    val isProtectedAppRule: Boolean get() = packages.size == 1 && Catalog.PROTECTED_APPS in packages

    init {
        require(ID_PATTERN.matches(id)) { "Invalid rule id: $id" }
        require(packages.isNotEmpty()) { "Rule $id has no target package" }
        require(isProtectedAppRule || packages.all { it == Catalog.SYSTEM_SCOPE || PACKAGE_PATTERN.matches(it) }) {
            "Rule $id has an invalid package name or mixes protected apps with fixed packages"
        }
        require(evidence.isNotBlank()) { "Rule $id has no evidence" }
        require(impact == null || !defaultOn || highRisk) {
            "Rule $id affects a service and must ship disabled unless highRisk"
        }
        require(!highRisk || (defaultOn && impact != null)) {
            "Rule $id is highRisk, so it must ship enabled and describe its impact"
        }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
