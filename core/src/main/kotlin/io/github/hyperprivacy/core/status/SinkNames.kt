package io.github.hyperprivacy.core.status

/**
 * Names of enforcement sinks, as reported in the status contract's sink table ([SinkReport]) and
 * in diagnostics-mode sink costs ([SinkPerf]). The hooks use them as `xposed.Sinks`.
 *
 * The status provider and the status relay accept sink rows only for names in [ALL], so a
 * reporting process cannot grow the sink tables (or system_server's cost counters) with names of
 * its own: every constant here must be in [ALL] (a test checks this by reflection).
 */
public object SinkNames {
    public const val DNS: String = "dns"
    public const val TLS: String = "tls"
    public const val CONNECT: String = "ip"
    public const val URL_JAVA_HOST: String = "url.java.host"
    public const val URL_JAVA: String = "url.java"
    public const val URL_OKHTTP: String = "url.okhttp"
    public const val URL_CRONET: String = "url.cronet"
    public const val URL_TURBONET: String = "url.turbonet"
    public const val URL_HTTP_ENGINE: String = "url.httpengine"
    public const val URL_WEBVIEW: String = "url.webview"
    public const val MIUI_ID_PROVIDER: String = "id.miui"

    /** Cost only: the URL rewrite hooks of every Cronet-family engine, measured together. */
    public const val CRONET_REWRITE: String = "cronet"

    /** Cost only: every METHOD rule's hooks, measured together in diagnostics mode. */
    public const val METHOD: String = "method"

    /** system_server: `SettingsState#getSettingLocked`, reported under `Catalog.SYSTEM_SCOPE`. */
    public const val SETTINGS_KEYS: String = "settings.keys"

    /** system_server: `AppsFilterBase#shouldFilterApplication`, reported under `Catalog.SYSTEM_SCOPE`. */
    public const val APP_LIST_FILTER: String = "applist.filter"

    /** system_server: `SettingsProvider#call`, the status relay of protected apps; under `Catalog.SYSTEM_SCOPE`. */
    public const val STATUS_RELAY: String = "status.relay"

    public const val MEDIA_DRM: String = "id.mediadrm"

    /** Hardware-fingerprint hook groups (`xposed.fp.HardwareFingerprintGuard`). */
    public const val FP_SENSOR: String = "fp.sensor"
    public const val FP_FSID: String = "fp.fsid"
    public const val FP_BATTERY: String = "fp.battery"

    /** Sensor-data hook groups (`xposed.fp.SensorDataGuard`): sensor events and direct channels, battery readings. */
    public const val SENSOR_EVENTS: String = "sensor.events"
    public const val SENSOR_BATTERY: String = "sensor.battery"

    /** Cost only: the origin execution sinks of protected apps (`xposed.origin.OriginNetGuard`). */
    public const val ORIGIN_OKHTTP: String = "origin okhttp exec"
    public const val ORIGIN_URL: String = "origin url"
    public const val ORIGIN_SOCKET: String = "origin socket connect"
    public const val ORIGIN_CHANNEL: String = "origin channel connect"
    public const val ORIGIN_VOLLEY: String = "origin volley perform"

    /** Cost only: WebView loads answered by origin (`xposed.origin.OriginWebGuard`). */
    public const val ORIGIN_WEBVIEW: String = "origin webview"

    /** Every sink above; see the class comment. */
    public val ALL: Set<String> = setOf(
        DNS, TLS, CONNECT, URL_JAVA_HOST, URL_JAVA, URL_OKHTTP, URL_CRONET, URL_TURBONET, URL_HTTP_ENGINE,
        URL_WEBVIEW, MIUI_ID_PROVIDER, CRONET_REWRITE, METHOD, SETTINGS_KEYS, APP_LIST_FILTER, STATUS_RELAY,
        MEDIA_DRM, FP_SENSOR, FP_FSID, FP_BATTERY, SENSOR_EVENTS, SENSOR_BATTERY, ORIGIN_OKHTTP, ORIGIN_URL,
        ORIGIN_SOCKET, ORIGIN_CHANNEL, ORIGIN_VOLLEY, ORIGIN_WEBVIEW,
    )

    public fun isKnown(sink: String): Boolean = sink in ALL
}
