package io.github.hyperprivacy.ui

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.MethodPattern
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.status.DisplayStatus

/** Fake data for @Preview functions only. */
private const val STORE_HITS = 128L
private const val SAMPLE_HITS = 42L
private const val HOUR_MILLIS = 3_600_000L

private val sampleApps = listOf(
    AppStatus("com.example.store", "应用商店", DisplayStatus.ACTIVE, STORE_HITS),
    AppStatus("com.example.themes", "主题商店", DisplayStatus.NOT_RUN, 0),
)

private val sampleRules = listOf(
    Rule(
        id = "sample.domains",
        packages = setOf("com.example.store"),
        title = Text("拦截统计域名", "Block analytics domains"),
        summary = Text("阻止应用商店连接统计上报服务器", "Block the store's analytics servers"),
        payload = RulePayload.Domains(listOf("tracking.example.com", "data.example.com")),
        category = Category.ANALYTICS,
        evidence = "traffic: com.example.store → tracking.example.com",
    ),
    Rule(
        id = "sample.sdk",
        packages = setOf("com.example.store", "com.example.themes"),
        title = Text("停用事件上报", "Disable event reporting"),
        summary = Text("将 SDK 的 track 系列方法置空", "No-op the SDK track methods"),
        payload = RulePayload.Method("com.example.sdk.Track", MethodPattern.parse("track*")),
        category = Category.ANALYTICS,
        evidence = "dex: Lcom/example/sdk/Track;",
    ),
    Rule(
        id = "sample.cloud",
        packages = setOf("com.example.store"),
        title = Text("停止上传应用指纹", "Stop uploading app fingerprints"),
        summary = Text("不再向云端上传已安装应用的特征", "Stop sending installed-app fingerprints"),
        payload = RulePayload.Domains(listOf("scan.example.com")),
        category = Category.APP_LIST,
        evidence = "traffic: com.example.store → scan.example.com",
        defaultOn = false,
        impact = Text("云查杀不可用", "Cloud scan unavailable"),
    ),
)

private val sampleItems = listOf(
    RuleItem(sampleRules[0], enabled = true, DisplayStatus.ACTIVE, hits = STORE_HITS, apps = sampleApps.take(1)),
    RuleItem(sampleRules[1], enabled = true, DisplayStatus.ACTIVE, hits = 0, apps = sampleApps),
    RuleItem(sampleRules[2], enabled = false, DisplayStatus.NOT_RUN, hits = 0, apps = sampleApps.take(1)),
)

internal fun sampleEntries(): List<RuleEntry> = listOf(
    RuleEntry(sampleItems[0], DisplayStatus.ACTIVE, STORE_HITS, RowMeta.App("应用商店")),
    RuleEntry(sampleItems[1], DisplayStatus.ACTIVE, 0, RowMeta.Apps(2)),
    RuleEntry(sampleItems[2], DisplayStatus.NOT_RUN, 0, RowMeta.None),
)

internal fun sampleItem(): RuleItem = sampleItems[0]

internal fun sampleState(module: ModuleState = ModuleState.ACTIVE): UiState {
    val appOrder = sampleApps.map { AppLabel(it.packageName, it.label) }
    return UiState(
        module = module,
        framework = "LSPosed 1.10",
        versionName = "0.1.0",
        byCategory = groupByCategory(sampleItems),
        byApp = groupByApp(sampleItems, appOrder),
        rules = sampleItems,
        enabledCount = 2,
        totalHits = STORE_HITS,
        observed = listOf(
            ObservedEndpointUi("应用商店", "tracking.example.com", "/v2/report", SAMPLE_HITS, now(), blocked = true),
            ObservedEndpointUi("主题商店", "stat.example.com", null, 1, now() - HOUR_MILLIS, blocked = false),
        ),
    )
}

private fun now() = System.currentTimeMillis()
