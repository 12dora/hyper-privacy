package io.github.hyperprivacy.xposed

import io.github.libxposed.api.XposedInterface

/** Per-process collaborators shared by every guard installed in one hooked app process. */
internal class GuardEnv(
    val xposed: XposedInterface,
    val log: HookLog,
    val packageName: String,
    val classLoader: ClassLoader,
    val switches: RuleSwitches,
    val reporter: StatusReporter,
)

/** Names of enforcement sinks, as reported in the status contract's sink table. */
internal object Sinks {
    const val DNS = "dns"
    const val TLS = "tls"
    const val CONNECT = "ip"
    const val URL_JAVA_HOST = "url.java.host"
    const val URL_JAVA = "url.java"
    const val URL_OKHTTP = "url.okhttp"
    const val URL_CRONET = "url.cronet"
    const val URL_TURBONET = "url.turbonet"
    const val URL_HTTP_ENGINE = "url.httpengine"
    const val URL_WEBVIEW = "url.webview"
    const val MIUI_ID_PROVIDER = "id.miui"

    /** system_server: `SettingsState#getSettingLocked`, reported under `Catalog.SYSTEM_SCOPE`. */
    const val SETTINGS_KEYS = "settings.keys"

    /** system_server: `AppsFilterBase#shouldFilterApplication`, reported under `Catalog.SYSTEM_SCOPE`. */
    const val APP_LIST_FILTER = "applist.filter"

    /** system_server: `SettingsProvider#call`, the status relay of protected apps; under `Catalog.SYSTEM_SCOPE`. */
    const val STATUS_RELAY = "status.relay"

    const val MEDIA_DRM = "id.mediadrm"
}
