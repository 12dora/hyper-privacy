package io.github.hyperprivacy.core.rule.catalog

/*
 * Target-package sets shared by several rules. Every set is sorted and listed one package per line so
 * a change to the set is a one-line diff. Sets used by a single rule with only a few packages stay
 * inline in that rule.
 */

/** Target packages of `bugly.domains`. */
internal val BUGLY_APPS: Set<String> = setOf(
    "com.sohu.inputmethod.sogou.xiaomi",
    "com.xiaomi.gamecenter.sdk.service",
    "com.xiaomi.shop",
    "com.xiaomi.smarthome",
    "com.xiaomi.youpin",
)

/** Target packages of `bytedance.applog`. */
internal val BYTEDANCE_APPLOG_APPS: Set<String> = setOf(
    "com.android.browser",
    "com.mi.globalbrowser",
    "com.mi.health",
    "com.xiaomi.gamecenter.sdk.service",
    "com.xiaomi.smarthome",
)

/** Target packages of `firebase.analytics`. */
internal val FIREBASE_ANALYTICS_APPS: Set<String> = setOf(
    "com.android.browser",
    "com.android.fileexplorer",
    "com.android.updater",
    "com.mi.globalbrowser",
    "com.miui.extraphoto",
    "com.miui.greenguard",
)

/** Target packages of `game-sdk.logs`. */
internal val GAME_SDK_LOGS_APPS: Set<String> = setOf(
    "com.miui.hybrid",
    "com.miui.securitycenter",
    "com.xiaomi.gamecenter.sdk.service",
    "com.xiaomi.minigame",
    "com.xiaomi.xmsf",
)

/** Target packages of `location.collection`. */
internal val LOCATION_COLLECTION_APPS: Set<String> = setOf(
    "com.mi.car.mobile",
    "com.mi.health",
    "com.miui.carlink",
    "com.miui.core",
    "com.miui.greenguard",
    "com.miui.securityadd",
    "com.miui.tsmclient",
    "com.miui.voiceassist",
    "com.xiaomi.finddevice",
    "com.xiaomi.metoknlp",
    "com.xiaomi.shop",
    "com.xiaomi.smarthome",
    "com.xiaomi.xmsf",
    "com.xiaomi.youpin",
)

/** Target packages of `location.sdk-stats`. */
internal val LOCATION_SDK_STATS_APPS: Set<String> = setOf(
    "com.mi.car.mobile",
    "com.miui.core",
    "com.miui.hybrid",
    "com.mobiletools.systemhelper",
    "com.xiaomi.finddevice",
    "com.xiaomi.metoknlp",
)

/** Target packages of `location.uplink-apps`: apps that bundle location SDKs but provide no positioning. */
internal val LOCATION_UPLINK_APPS: Set<String> = setOf(
    "com.miui.hybrid",
    "com.mobiletools.systemhelper",
    "com.xiaomi.shop",
    "com.xiaomi.smarthome",
)

/**
 * Target packages of `location.uplink`: the positioning and Find Device services, kept apart from
 * [LOCATION_UPLINK_APPS] because blocking there is not live-verified.
 */
internal val LOCATION_SERVICE_APPS: Set<String> = setOf(
    "com.xiaomi.finddevice",
    "com.xiaomi.metoknlp",
)

/** Target packages of `mistat.domains`. */
internal val MISTAT_APPS: Set<String> = setOf(
    "com.miui.player",
    "com.xiaomi.market",
    "com.xiaomi.smarthome",
    "com.xiaomi.xmsf",
)

/** Apps bundling the OneTrack SDK (Xiaomi first-party telemetry); the base for the SDK-wide sets below. */
internal val ONETRACK_APPS: Set<String> = setOf(
    "com.android.browser",
    "com.android.calendar",
    "com.android.camera",
    "com.android.contacts",
    "com.android.deskclock",
    "com.android.fileexplorer",
    "com.android.htmlviewer",
    "com.android.incallui",
    "com.android.intentresolver",
    "com.android.mms",
    "com.android.permissioncontroller",
    "com.android.phone",
    "com.android.photopicker",
    "com.android.printspooler",
    "com.android.providers.downloads",
    "com.android.providers.downloads.ui",
    "com.android.provision",
    "com.android.quicksearchbox",
    "com.android.settings",
    "com.android.soundrecorder",
    "com.android.systemui",
    "com.android.thememanager",
    "com.android.updater",
    "com.duokan.phone.remotecontroller",
    "com.fido.asm",
    "com.lbe.security.miui",
    "com.mi.car.mobile",
    "com.mi.globalbrowser",
    "com.mi.health",
    "com.milink.service",
    "com.miui.accessibility",
    "com.miui.aod",
    "com.miui.backup",
    "com.miui.bugreport",
    "com.miui.calculator",
    "com.miui.carlink",
    "com.miui.cleanmaster",
    "com.miui.cloudbackup",
    "com.miui.cloudservice",
    "com.miui.compass",
    "com.miui.contentextension",
    "com.miui.core",
    "com.miui.extraphoto",
    "com.miui.greenguard",
    "com.miui.guardprovider",
    "com.miui.huanji",
    "com.miui.hybrid",
    "com.miui.mediaeditor",
    "com.miui.mediaviewer",
    "com.miui.mishare.connectivity",
    "com.miui.misightservice",
    "com.miui.miwallpaper",
    "com.miui.newmidrive",
    "com.miui.notes",
    "com.miui.notification",
    "com.miui.passwords",
    "com.miui.personalassistant",
    "com.miui.player",
    "com.miui.powerinsight",
    "com.miui.powerkeeper",
    "com.miui.screenrecorder",
    "com.miui.screenshot",
    "com.miui.securityadd",
    "com.miui.securitycenter",
    "com.miui.securitycore",
    "com.miui.thirdappassistant",
    "com.miui.touchassistant",
    "com.miui.tsmclient",
    "com.miui.uireporter",
    "com.miui.virtualsim",
    "com.miui.voiceassist",
    "com.miui.voicetrigger",
    "com.miui.wmsvc",
    "com.miui.yellowpage",
    "com.xiaomi.account",
    "com.xiaomi.aiasst.service",
    "com.xiaomi.aiasst.vision",
    "com.xiaomi.aicr",
    "com.xiaomi.aiservice",
    "com.xiaomi.barrage",
    "com.xiaomi.bluetooth",
    "com.xiaomi.digitalkey",
    "com.xiaomi.finddevice",
    "com.xiaomi.gamecenter.sdk.service",
    "com.xiaomi.healthkit",
    "com.xiaomi.hypercomm",
    "com.xiaomi.joyose",
    "com.xiaomi.macro",
    "com.xiaomi.market",
    "com.xiaomi.metoknlp",
    "com.xiaomi.mi_connect_service",
    "com.xiaomi.minigame",
    "com.xiaomi.mirror",
    "com.xiaomi.mis",
    "com.xiaomi.misettings",
    "com.xiaomi.payment",
    "com.xiaomi.phone",
    "com.xiaomi.scanner",
    "com.xiaomi.shop",
    "com.xiaomi.simactivate.service",
    "com.xiaomi.smarthome",
    "com.xiaomi.type",
    "com.xiaomi.vipaccount",
    "com.xiaomi.xmsf",
    "com.xiaomi.youpin",
    "miui.systemui.plugin",
)

/** Target packages of `onetrack.pubsub`. */
internal val ONETRACK_PUBSUB_APPS: Set<String> = setOf(
    "com.android.camera",
    "com.android.phone",
    "com.android.soundrecorder",
    "com.mi.globalbrowser",
    "com.miui.accessibility",
    "com.miui.aod",
    "com.miui.gallery",
    "com.miui.home",
    "com.miui.mediaeditor",
    "com.miui.misightservice",
    "com.miui.miwallpaper",
    "com.miui.notes",
    "com.miui.notification",
    "com.miui.powerinsight",
    "com.miui.thirdappassistant",
    "com.xiaomi.aiservice",
    "com.xiaomi.hypercomm",
    "com.xiaomi.misettings",
    "miui.systemui.plugin",
)

/** Target packages of `payment-sdk.logs`. */
internal val PAYMENT_SDK_LOGS_APPS: Set<String> = setOf(
    "com.mi.health",
    "com.miui.hybrid",
    "com.miui.player",
    "com.miui.tsmclient",
    "com.xiaomi.gamecenter.sdk.service",
    "com.xiaomi.payment",
    "com.xiaomi.shop",
    "com.xiaomi.smarthome",
    "com.xiaomi.xmsf",
    "com.xiaomi.youpin",
)

/** Target packages of `qq-opensdk.stats`. */
internal val QQ_OPENSDK_STATS_APPS: Set<String> = setOf(
    "com.baidu.input_mi",
    "com.iflytek.inputmethod.miui",
    "com.miui.carlink",
    "com.miui.hybrid",
    "com.miui.player",
    "com.miui.voiceassist",
    "com.sohu.inputmethod.sogou.xiaomi",
    "com.xiaomi.gamecenter.sdk.service",
    "com.xiaomi.shop",
    "com.xiaomi.smarthome",
    "com.xiaomi.vipaccount",
)

/** Target packages of `securitycenter.stats,securitycenter.promo-cards`. */
internal val SECURITY_CENTER_APPS: Set<String> = setOf(
    "com.miui.cleanmaster",
    "com.miui.securitycenter",
)

/** Target packages of `sensorsdata.domains`. */
internal val SENSORSDATA_APPS: Set<String> = setOf(
    "com.android.contacts",
    "com.miui.virtualsim",
    "com.unionpay.tsmservice.mi",
    "com.xiaomi.gamecenter.sdk.service",
    "com.xiaomi.market",
    "com.xiaomi.xmsf",
)

/** Target packages of `sentry.domains`. */
internal val SENTRY_APPS: Set<String> = setOf(
    "com.android.quicksearchbox",
    "com.mi.car.mobile",
    "com.xiaomi.shop",
    "com.xiaomi.smarthome",
    "com.xiaomi.youpin",
)

/** Target packages of `shop.tracking,huawei.analytics`. */
internal val SHOP_APPS: Set<String> = setOf(
    "com.xiaomi.shop",
    "com.xiaomi.smarthome",
    "com.xiaomi.youpin",
)

/** Target packages of `tencent.beacon`. */
internal val TENCENT_BEACON_APPS: Set<String> = setOf(
    "com.android.thememanager",
    "com.iflytek.inputmethod.miui",
    "com.miui.hybrid",
    "com.miui.player",
    "com.miui.voiceassist",
    "com.sohu.inputmethod.sogou.xiaomi",
    "com.xiaomi.gamecenter.sdk.service",
    "com.xiaomi.shop",
    "com.xiaomi.smarthome",
    "com.xiaomi.vipaccount",
)

/** Target packages of `tencent.rdelivery-report,qimei.domains`. */
internal val TENCENT_RDELIVERY_APPS: Set<String> = setOf(
    "com.miui.player",
    "com.sohu.inputmethod.sogou.xiaomi",
)

/** Target packages of `thirdparty.sdk-logs`. */
internal val THIRDPARTY_SDK_LOGS_APPS: Set<String> = setOf(
    "com.android.providers.downloads.ui",
    "com.mi.car.mobile",
    "com.miui.hybrid",
    "com.miui.player",
    "com.miui.securityadd",
    "com.xiaomi.minigame",
)

/** Target packages of `xiaoai.tracker`. */
internal val XIAOAI_TRACKER_APPS: Set<String> = setOf(
    "com.android.camera",
    "com.android.soundrecorder",
    "com.mi.health",
    "com.miui.accessibility",
    "com.miui.carlink",
    "com.miui.notes",
    "com.miui.personalassistant",
    "com.miui.voiceassist",
    "com.miui.voicetrigger",
    "com.xiaomi.aiasst.service",
    "com.xiaomi.aiasst.vision",
    "com.xiaomi.aicr",
    "com.xiaomi.mibrain.speech",
    "com.xiaomi.shop",
    "com.xiaomi.smarthome",
)

/** Target packages of `xiaomi-analytics.method`. */
internal val XIAOMI_ANALYTICS_APPS: Set<String> = setOf(
    "com.miui.personalassistant",
    "com.miui.player",
    "com.miui.securitycenter",
    "com.miui.voiceassist",
    "com.xiaomi.gamecenter.sdk.service",
    "com.xiaomi.market",
)

/**
 * OneTrack apps whose `OneTrack` class is absent or stripped of `track*` methods (verified on device and
 * in dex class_defs), so the method hook has nothing to patch there. Their uploads are still covered by
 * the OneTrack domain rule.
 */
internal val ONETRACK_WITHOUT_SDK_APPS: Set<String> = setOf(
    "com.android.updater",
    "com.mi.car.mobile",
    "com.miui.cloudbackup",
    "com.miui.compass",
    "com.miui.passwords",
    "com.miui.screenrecorder",
    "com.miui.securitycore",
    "com.miui.touchassistant",
    "com.miui.uireporter",
    "com.xiaomi.aiservice",
    "com.xiaomi.bluetooth",
    "com.xiaomi.hypercomm",
    "com.xiaomi.metoknlp",
    "com.xiaomi.mis",
    "com.xiaomi.phone",
)

/** OneTrack apps that do not embed the Xiaomi ad SDK. */
internal val AD_SDK_EXCLUDED_APPS: Set<String> = setOf(
    "com.xiaomi.bluetooth",
)

/** Apps with the OneTrack class, the target of the `OneTrack.track*` hook. */
internal val ONETRACK_SDK_APPS: Set<String> = ONETRACK_APPS - ONETRACK_WITHOUT_SDK_APPS

/** Apps bundling the Xiaomi ad SDK (ad request and impression reporting). */
internal val AD_SDK_APPS: Set<String> = ONETRACK_APPS - AD_SDK_EXCLUDED_APPS

/**
 * Packages the app-wide `httpdns.resolvers` rule never targets although other rules inject into
 * them: vendor services that bind the account, a payment card or the device to a backend, or that
 * keep the device reachable and secure. A refused resolver there risks a failed login, card or
 * key operation, a lost push channel or a broken connectivity check, for little privacy gain.
 */
internal val HTTPDNS_EXCLUDED_APPS: Set<String> = setOf(
    "com.android.phone", // telephony
    "com.android.provision", // setup wizard (account sign-in, activation)
    "com.android.settings",
    "com.android.updater", // OTA updates
    "com.fido.asm", // FIDO authenticator
    "com.lbe.security.miui", // permission manager
    "com.miui.cloudbackup", // Xiaomi Cloud backup
    "com.miui.cloudservice", // Xiaomi Cloud sync
    "com.miui.guardprovider", // security scan
    "com.miui.newmidrive", // Xiaomi Cloud drive
    "com.miui.passwords", // password manager / autofill
    "com.miui.securityadd", // security center
    "com.miui.securitycenter", // security center
    "com.miui.securitycore", // security core (second space, app lock)
    "com.miui.tsmclient", // wallet card emulation (TSM)
    "com.miui.virtualsim", // virtual SIM
    "com.unionpay.tsmservice.mi", // UnionPay TSM
    "com.xiaomi.account", // Xiaomi account
    "com.xiaomi.digitalkey", // digital car key
    "com.xiaomi.finddevice", // Find Device
    "com.xiaomi.payment", // Mi Pay / wallet
    "com.xiaomi.simactivate.service", // SIM activation
    "com.xiaomi.xmsf", // MiPush
)
