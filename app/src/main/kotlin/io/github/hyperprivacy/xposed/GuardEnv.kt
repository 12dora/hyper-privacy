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

    /** Hardware-fingerprint hook groups (`xposed.fp.HardwareFingerprintGuard`). */
    const val FP_SENSOR = "fp.sensor"
    const val FP_FSID = "fp.fsid"
    const val FP_BATTERY = "fp.battery"

    /**
     * Every sink above. The status provider and the status relay accept sink reports only for
     * these, so a reporting process cannot grow the sink tables with names of its own: a new sink
     * must be added here too.
     */
    private val ALL: Set<String> = setOf(
        DNS, TLS, CONNECT, URL_JAVA_HOST, URL_JAVA, URL_OKHTTP, URL_CRONET, URL_TURBONET, URL_HTTP_ENGINE,
        URL_WEBVIEW, MIUI_ID_PROVIDER, SETTINGS_KEYS, APP_LIST_FILTER, STATUS_RELAY, MEDIA_DRM, FP_SENSOR,
        FP_FSID, FP_BATTERY,
    )

    fun isKnown(sink: String): Boolean = sink in ALL
}
