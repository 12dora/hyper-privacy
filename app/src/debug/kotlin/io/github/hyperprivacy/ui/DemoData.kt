package io.github.hyperprivacy.ui

import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.core.conflict.OverlapArea
import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.scope.DeviceCoverage
import io.github.hyperprivacy.core.status.DisplayStatus
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.tracker.AppDefaults
import io.github.hyperprivacy.core.tracker.TrackerCategory
import io.github.hyperprivacy.core.tracker.TrackerOrigin
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.TrackerRules
import io.github.hyperprivacy.core.tracker.TrackerSdk
import io.github.hyperprivacy.core.tracker.Trackers
import io.github.hyperprivacy.ui.apps.AppRuleUi
import io.github.hyperprivacy.ui.apps.AppsUi
import io.github.hyperprivacy.ui.apps.ScanState
import io.github.hyperprivacy.ui.apps.SdkUi
import io.github.hyperprivacy.ui.apps.SuspectUi
import io.github.hyperprivacy.ui.apps.UserAppUi

/** What the user changed in demo mode; everything else is the sample data's default. Main thread only. */
internal class DemoSwitches(
    var pausedUntil: Long,
    var selfHidden: Boolean = true,
    var diagnosticMode: Boolean = false,
    var conflictDismissed: Boolean = false,
    var observedCleared: Boolean = false,
    /** The user's own allowlist; `null`: the built-in one. */
    var allowlist: Set<String>? = null,
) {
    val rules = HashMap<String, Boolean>()
    val appSwitches = HashMap<String, Boolean>()
    val protected = HashMap<String, Boolean>()
}

/** One sample user app: which SDK categories it carries and whether it starts protected. */
private class DemoUserApp(
    val packageName: String,
    val label: Text,
    val sdks: List<Pair<TrackerCategory, Int>>,
    val protected: Boolean,
    val suspects: List<SuspectUi> = emptyList(),
    val suspectHits: Long = 0,
    /** Starts with app-list hiding on, so the strict-mode row below it shows. */
    val hideApps: Boolean = false,
)

/**
 * The sample data of demo mode, built from the real rule catalog and tracker database so every
 * screen shows realistic content: about a hundred rules for a set of installed system apps, a few
 * hits, one rule to review, three protected user apps with detected SDKs and suspected ad
 * components, and three overlapping modules.
 */
internal class DemoData(private val language: String) {
    private val labels: List<AppLabel> = SYSTEM_APPS.map { (pkg, label) -> AppLabel(pkg, label.resolve(language)) }
    private val rules: List<Rule> = DeviceCoverage.applicableRules(Catalog.rules, labels.mapTo(HashSet()) { it.packageName })
    private val sample = sampleState()

    fun state(switches: DemoSwitches): UiState {
        var reviewed = false
        val items = rules.mapIndexed { index, rule ->
            val enabled = switches.rules[rule.id] ?: rule.defaultOn
            val review = enabled && !reviewed && index >= REVIEW_FROM
            if (review) reviewed = true
            item(rule, index, enabled, review)
        }
        val users = apps(switches)
        return UiState(
            loaded = true,
            module = ModuleState.ACTIVE,
            framework = sample.framework,
            versionName = BuildConfig.VERSION_NAME,
            byCategory = groupByCategory(items),
            apps = summarizeApps(items, labels),
            rules = items,
            enabledCount = items.count { it.enabled },
            totalHits = items.sumOf { it.hits },
            attention = attentionOf(items, suspected = 0),
            diagnosticMode = switches.diagnosticMode,
            selfHidden = switches.selfHidden,
            observed = if (switches.observedCleared) emptyList() else sample.observed,
            pausedUntil = switches.pausedUntil,
            otherProtectionOn = true,
            protectedApps = users.apps.count { it.protected },
            conflict = ConflictUi.of(
                CONFLICTS.map { (pkg, label, areas) -> ConflictModule(pkg, label.resolve(language), areas) },
                appListHidingOn = switches.selfHidden,
            ).takeUnless { switches.conflictDismissed },
        )
    }

    private fun item(rule: Rule, index: Int, enabled: Boolean, review: Boolean): RuleItem {
        val hits = if (enabled && index % HIT_EVERY == 0) HIT_BASE + index * HIT_STEP % HIT_SPREAD else 0L
        val status = if (enabled) DisplayStatus.ACTIVE else DisplayStatus.NOT_RUN
        val health = when {
            review -> Health.NEEDS_REVIEW
            enabled -> Health.OK
            else -> Health.NOT_RUN
        }
        val targets = labels.filter { it.packageName in rule.packages }
        val apps = targets.mapIndexed { i, app ->
            AppStatus(app.packageName, app.label, status, if (i == 0) hits else 0L, health)
        }
        return RuleItem(rule, enabled, status, hits, apps, health)
    }

    fun apps(switches: DemoSwitches): AppsUi {
        val apps = USER_APPS.map { app -> userApp(app, switches) }
        return AppsUi(
            loaded = true,
            apps = apps,
            scanned = apps.size,
            total = apps.size,
            defaults = AppDefaults.read(switches.appSwitches),
            allowlist = switches.allowlist ?: AppListConfig.DEFAULT_ALLOWLIST,
            allowlistCustom = switches.allowlist != null,
        )
    }

    /** Turns protection on or off like the real controller: on applies the default settings first. */
    fun setProtected(switches: DemoSwitches, packageName: String, on: Boolean) {
        val app = USER_APPS.firstOrNull { it.packageName == packageName } ?: return
        if (on) {
            val ids = app.sdks.flatMap { (category, count) -> sdksOf(category, count) }.map { it.id }
            switches.appSwitches += AppDefaults.read(switches.appSwitches).applyTo(switches.appSwitches, packageName, ids)
        }
        switches.protected[packageName] = on
    }

    private fun userApp(app: DemoUserApp, switches: DemoSwitches): UserAppUi {
        val pkg = app.packageName
        val sdks = app.sdks.flatMap { (category, count) -> sdksOf(category, count) }.mapIndexed { i, sdk ->
            val blocked = switches.appSwitches[TrackerProtection.blockKey(pkg, sdk.id)] ?: sdk.defaultBlock
            SdkUi(
                id = sdk.id,
                name = sdk.displayName,
                category = sdk.category,
                blockable = sdk.switchable,
                defaultBlock = sdk.defaultBlock,
                blocked = sdk.switchable && blocked,
                hits = if (app.protected && sdk.switchable && blocked) SDK_HITS / (i + 1) else 0L,
            )
        }
        val appRules = Catalog.protectedAppRules.map { rule ->
            val key = TrackerRules.appRuleKey(pkg, rule.id)
            AppRuleUi(rule.id, rule.title, rule.summary, switches.appSwitches[key] ?: rule.defaultOn, hits = 0L)
        }
        val suspects = app.suspects.map { suspect ->
            suspect.copy(blocked = switches.appSwitches[TrackerOrigin.suspectKey(pkg, suspect.root)] ?: suspect.blocked)
        }
        return UserAppUi(
            packageName = pkg,
            label = app.label.resolve(language),
            scan = ScanState.DONE,
            sdks = sdks,
            appRules = appRules,
            protected = switches.protected[pkg] ?: app.protected,
            scoped = true,
            requesting = false,
            hideApps = switches.appSwitches[AppListConfig.hideKey(pkg)] ?: app.hideApps,
            strict = switches.appSwitches[AppListConfig.strictKey(pkg)] ?: false,
            suspects = suspects,
            suspectHits = app.suspectHits,
        )
    }

    private fun sdksOf(category: TrackerCategory, count: Int): List<TrackerSdk> =
        Trackers.all.filter { it.category == category }.take(count)

    private companion object {
        /** The rule marked "to review" is the first enabled one from this position on. */
        const val REVIEW_FROM = 5
        const val HIT_EVERY = 7
        const val HIT_BASE = 3L
        const val HIT_STEP = 41L
        const val HIT_SPREAD = 900L
        const val SDK_HITS = 64L
        const val SUSPECT_HITS = 6L

        /** Overlapping modules; one does several things, and ads has more modules than the hint names. */
        val CONFLICTS: List<Triple<String, Text, List<OverlapArea>>> = listOf(
            Triple("com.example.hideapps", Text("示例模块", "Sample module"), listOf(OverlapArea.APP_LIST)),
            Triple("com.example.adkit", Text("Example AdKit", "Example AdKit"), listOf(OverlapArea.ADS)),
            Triple(
                "com.example.cleaner",
                Text("净化助手", "Cleaner"),
                listOf(OverlapArea.ADS, OverlapArea.PRIVACY, OverlapArea.DEVICE_ID),
            ),
            Triple("com.example.noads", Text("去广告", "No Ads"), listOf(OverlapArea.ADS)),
            Triple("com.example.splashskip", Text("开屏跳过", "Splash Skip"), listOf(OverlapArea.ADS)),
        )

        /** Installed system apps of the demo device, with neutral display names. */
        val SYSTEM_APPS: List<Pair<String, Text>> = listOf(
            "com.xiaomi.market" to Text("应用商店", "App Store"),
            "com.android.browser" to Text("浏览器", "Browser"),
            "com.mi.globalbrowser" to Text("浏览器（国际版）", "Browser (global)"),
            "com.miui.player" to Text("音乐", "Music"),
            "com.miui.securitycenter" to Text("手机管家", "Security"),
            "com.miui.securitycore" to Text("安全核心组件", "Security core"),
            "com.miui.guardprovider" to Text("安全守护服务", "Guard service"),
            "com.xiaomi.smarthome" to Text("智能家居", "Smart home"),
            "com.xiaomi.shop" to Text("商城", "Store"),
            "com.xiaomi.youpin" to Text("优选", "Picks"),
            "com.miui.voiceassist" to Text("语音助手", "Voice assistant"),
            "com.miui.personalassistant" to Text("智能助理", "Assistant"),
            "com.mi.health" to Text("运动健康", "Health"),
            "com.xiaomi.gamecenter.sdk.service" to Text("游戏服务", "Game service"),
            "com.android.updater" to Text("系统更新", "System update"),
            "com.xiaomi.xmsf" to Text("推送服务", "Push service"),
            "com.miui.analytics" to Text("系统分析服务", "System analytics"),
            "com.miui.systemAdSolution" to Text("系统广告服务", "System ad service"),
            "com.xiaomi.payment" to Text("支付服务", "Payments"),
            "com.miui.hybrid" to Text("快应用服务", "Instant apps"),
            "com.miui.misightservice" to Text("系统洞察服务", "Insight service"),
            "com.xiaomi.finddevice" to Text("查找设备", "Find device"),
            "com.miui.tsmclient" to Text("钱包", "Wallet"),
            "com.xiaomi.vipaccount" to Text("社区", "Community"),
            "com.sohu.inputmethod.sogou.xiaomi" to Text("输入法", "Keyboard"),
            "com.miui.core" to Text("系统核心组件", "System core"),
            "com.android.camera" to Text("相机", "Camera"),
            "com.android.mms" to Text("短信", "Messages"),
            "com.android.contacts" to Text("联系人", "Contacts"),
            "com.android.phone" to Text("电话服务", "Phone services"),
            "com.android.fileexplorer" to Text("文件管理", "Files"),
            "com.android.thememanager" to Text("主题", "Themes"),
            "com.android.settings" to Text("设置", "Settings"),
            "com.android.quicksearchbox" to Text("搜索", "Search"),
            "com.android.soundrecorder" to Text("录音机", "Recorder"),
            "com.android.providers.downloads.ui" to Text("下载管理", "Downloads"),
            "com.android.providers.downloads" to Text("下载服务", "Download service"),
            "com.android.provision" to Text("开机向导", "Setup"),
            "com.miui.notes" to Text("笔记", "Notes"),
            "com.miui.cleanmaster" to Text("清理", "Cleaner"),
            "com.miui.cloudbackup" to Text("云备份", "Cloud backup"),
            "com.miui.cloudservice" to Text("云服务", "Cloud service"),
            "com.miui.powerkeeper" to Text("电量与性能", "Battery"),
            "com.miui.powerinsight" to Text("电量洞察", "Power insight"),
            "com.miui.voicetrigger" to Text("语音唤醒", "Voice wake-up"),
            "com.miui.passwords" to Text("密码管理", "Passwords"),
            "com.miui.extraphoto" to Text("相册编辑", "Photo editor"),
            "com.miui.mediaeditor" to Text("媒体编辑", "Media editor"),
            "com.miui.accessibility" to Text("无障碍", "Accessibility"),
            "com.miui.miwallpaper" to Text("壁纸", "Wallpaper"),
            "com.miui.aod" to Text("息屏显示", "Always-on display"),
            "com.miui.compass" to Text("指南针", "Compass"),
            "com.miui.bugreport" to Text("问题反馈", "Feedback"),
            "com.miui.screenrecorder" to Text("屏幕录制", "Screen recorder"),
            "com.miui.newmidrive" to Text("云盘", "Cloud drive"),
            "com.miui.virtualsim" to Text("全球上网", "Global data"),
            "com.miui.securityadd" to Text("系统安全组件", "Security add-on"),
            "com.miui.greenguard" to Text("家人守护", "Family guard"),
            "com.miui.carlink" to Text("车载互联", "Car link"),
            "com.miui.notification" to Text("通知管理", "Notifications"),
            "com.miui.touchassistant" to Text("悬浮球", "Quick ball"),
            "com.miui.thirdappassistant" to Text("应用助手", "App assistant"),
            "com.miui.uireporter" to Text("界面统计", "UI reporter"),
            "com.milink.service" to Text("互联服务", "Connect service"),
            "com.xiaomi.account" to Text("账号", "Account"),
            "com.xiaomi.scanner" to Text("扫一扫", "Scanner"),
            "com.xiaomi.simactivate.service" to Text("SIM 卡激活", "SIM activation"),
            "com.xiaomi.metoknlp" to Text("位置服务", "Location service"),
            "com.xiaomi.minigame" to Text("小游戏", "Mini games"),
            "com.xiaomi.hypercomm" to Text("跨设备通信", "Cross-device"),
            "com.xiaomi.bluetooth" to Text("蓝牙", "Bluetooth"),
            "com.xiaomi.aiservice" to Text("智能服务", "AI service"),
            "com.xiaomi.aicr" to Text("智能识别", "AI recognition"),
            "com.xiaomi.aiasst.service" to Text("智能助手服务", "Assistant service"),
            "com.xiaomi.aiasst.vision" to Text("智能视觉", "Vision"),
            "com.xiaomi.registration" to Text("设备注册", "Registration"),
            "com.xiaomi.misettings" to Text("更多设置", "More settings"),
            "com.xiaomi.digitalkey" to Text("数字钥匙", "Digital key"),
            "com.mi.car.mobile" to Text("汽车", "Car"),
            "com.baidu.input_mi" to Text("输入法 2", "Keyboard 2"),
            "com.iflytek.inputmethod.miui" to Text("输入法 3", "Keyboard 3"),
            "com.mobiletools.systemhelper" to Text("系统助手", "System helper"),
            "com.lbe.security.miui" to Text("权限管理", "Permissions"),
        )

        val USER_APPS: List<DemoUserApp> = listOf(
            DemoUserApp(
                packageName = "com.example.reader",
                label = Text("阅读", "Reader"),
                sdks = listOf(TrackerCategory.ADS to 2, TrackerCategory.ANALYTICS to 1, TrackerCategory.IDENTIFICATION to 1),
                protected = true,
                hideApps = true,
                suspects = listOf(
                    SuspectUi(
                        root = "com.example.reader.adkit.",
                        components = listOf(
                            "com.example.reader.adkit.SplashAdActivity",
                            "com.example.reader.adkit.RewardVideoActivity",
                        ),
                        blocked = false,
                    ),
                ),
            ),
            DemoUserApp(
                packageName = "com.example.shop",
                label = Text("购物", "Shopping"),
                sdks = listOf(TrackerCategory.ADS to 1, TrackerCategory.ATTRIBUTION to 1, TrackerCategory.PUSH to 1),
                protected = true,
            ),
            DemoUserApp(
                packageName = "com.example.video",
                label = Text("视频", "Video"),
                sdks = listOf(TrackerCategory.ANALYTICS to 2, TrackerCategory.CRASH to 1),
                protected = true,
                suspects = listOf(
                    SuspectUi("com.example.video.promo.", listOf("com.example.video.promo.InterstitialActivity"), true),
                ),
                suspectHits = SUSPECT_HITS,
            ),
            DemoUserApp(
                packageName = "com.example.weather",
                label = Text("天气", "Weather"),
                sdks = listOf(TrackerCategory.LOCATION to 1, TrackerCategory.ADS to 1),
                protected = false,
            ),
            // Partner apps on the built-in allowlist, under neutral names.
            DemoUserApp("com.tencent.mm", Text("聊天", "Chat"), sdks = emptyList(), protected = false),
            DemoUserApp("com.eg.android.AlipayGphone", Text("支付", "Pay"), sdks = emptyList(), protected = false),
            DemoUserApp(
                packageName = "com.example.notes",
                label = Text("便签", "Notes"),
                sdks = emptyList(),
                protected = false,
            ),
        )
    }
}
