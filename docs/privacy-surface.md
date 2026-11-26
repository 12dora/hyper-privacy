# HyperOS 隐私上报面与规则覆盖

- 对象：小米 HyperOS 4（Android 17）真机，318 个系统 / 厂商预装包
- 目标：拦截全部隐私上报通道，且不影响任何厂商服务
- 结论：规则表共 102 条，覆盖 6 类数据通道。其中 90 条默认开启，含 10 条「高风险」规则（拦截会有副作用，但泄露的数据过于敏感，所以默认阻止并写明影响）；12 条会影响安全、支付、账号、定位等功能、未经实测或属于用户主动参与的项目，标注影响并默认关闭。已知未覆盖的通道见「残余风险」。规则表经过一轮独立对抗评审，采纳与驳回的结果见第 8 节

## 1. 方法

四个独立来源交叉印证。每条规则的 `evidence` 字段至少引用其中一个来源。

| 来源 | 做法 | 能发现 | 局限 |
|---|---|---|---|
| DEX 静态扫描 | 逐包解出全部 dex：按包名前缀识别 SDK（OneTrack、Bugly、Beacon、qimei、sofire 等）；提取明文主机、URL 与 API 路径常量 | SDK 是否存在、硬编码端点 | 运行时下发、加密或拼接的地址查不到 |
| 隐藏主机解码 | 还原以 `byte[]` 常量拼出的主机名 | OneTrack 在 102 个应用中隐藏 `tracking.*.miui.com`，在 68 个应用中隐藏 `sdkconfig.*.xiaomi.com`；`sdkconfig.ad.*` 隐藏在 34–36 个应用中 | 只识别已知的编码方式 |
| 反编译定位 | 用 jadx 反编译安全中心、电量与性能、应用商店、负一屏、社区、百度输入法、小米商城、服务框架、短信、讯飞输入法，把路径常量追到具体主机、调用方与请求体 | 同一主机上哪些路径是上报、哪些是服务；上报内容 | 只覆盖反编译过的包 |
| 流量归因 | 3 次抓包：空闲 20 分钟；在百度 / 搜狗 / 讯飞输入法中打字；依次启动 28 个厂商应用。按 uid 归属 DNS 与 TLS SNI | 实际发生的连接 | uid 1000 约 50 个包共用一个 uid，只能归到整组；直连 IP 只能看到地址 |
| 权限 / AppOps / Settings | 汇总已授予权限、近 7 天 AppOps 访问记录和 Settings 中的上报开关 | 谁有能力、谁实际读取了数据 | 不代表一定会上传 |

判定口径：

- **上报**：事件埋点、统计、A/B 曝光记录、日志 / 崩溃 / 性能上传、广告请求与回传、应用列表 / 应用活动上传、设备指纹采集、定位与 Wi-Fi / 基站采集、输入法使用统计。广告投放本身也按上报处理，在范围内。
- **服务**：账号、推送注册与下发、云同步、查找设备、系统与应用更新、应用商店浏览与下载、功能所需的云配置、天气、定位查询、云输入与语音识别、病毒库更新、支付、客服。服务端点一律不拦截。
- 同一主机既有服务又有上报时，用 URL 规则只拦上报路径。上报通道一旦被拦会导致功能失效的，规则标注影响。影响较小而泄露的数据极其敏感（通讯录、短信原文、应用列表、设备指纹、短信链接）的，标为「高风险」并默认开启，影响仍会显示；其余（例如云查杀、用户主动反馈）默认关闭。

## 2. 规则总览

| 类别 | 条数 | 机制分布 |
|---|---:|---|
| 统计分析 ANALYTICS | 37 | 域名 18 · URL 16 · 方法 2 · 断网 1 |
| 广告 ADS | 11 | 域名 7 · URL 3 · 断网 1 |
| 诊断 DIAGNOSTICS | 26 | 域名 16 · URL 10 |
| 设备标识 DEVICE_ID | 13 | 域名 4 · URL 4 · 断网 2 · 标识 2 · 方法 1 |
| 应用列表 APP_LIST | 7 | URL 5 · 域名 1 · 方法 1 |
| 个人数据 PERSONAL_DATA | 8 | URL 5 · 域名 3 |

只对自有 uid（≥ 10000 且不共享）的纯上报应用整体断网：系统分析服务（`com.miui.analytics`）、系统广告服务（`com.miui.systemAdSolution`），默认关闭的 SIM 激活服务（`com.xiaomi.simactivate.service`），以及联通终端管理（`com.mobiletools.systemhelper`，独占 uid）。其余规则都注入目标进程，在 DNS、TLS SNI 与 HTTP 客户端层生效。

默认关闭、标注影响的 12 条。拦掉它们会影响安全、支付、账号、定位等功能，或属于用户主动参与的项目（地震志愿者、问题反馈）。其中 `payment.checkout-track`、`payment-sdk.logs`、`location.uplink` 未在真机上验证，`simactivate.tee-report` 的 Passport 回退尚未证实：

| 规则 | 拦截内容 | 影响 |
|---|---|---|
| `securitycenter.cloud-scan` | `virus-engine.sec.miui.com` 云查杀 | 云端查杀失效，本地病毒库仍可用 |
| `securitycenter.quake-sensor` | 地震监测志愿者的传感器数据 | 已加入的志愿者不再贡献数据 |
| `feedback.upload` | `feedback.miui.com`、`bugreport.xiaomi.net` | 「服务与反馈」无法提交问题 |
| `simactivate.firewall` | SIM 激活服务整体断网 | 基于 SIM 的快捷登录与号码认证可能失效 |
| `simactivate.tee-report` | `tz.sec.xiaomi.com/session` 在 TEE 校验失败时上报 OAID、设备 ID 与 CPU ID | `/session` 可能是 TEE 不可用时的 Passport 回退，可能影响小米账号登录；未证实，故默认关闭 |
| `weather.ads` | `wtradv.market.xiaomi.com` 整域 | 该主机同时提供生活指数详情 `/v1/index/indexDetail` 与资讯页 `/v1/page`，这些内容可能无法加载 |
| `payment.checkout-track` | `track.ucashier.mipay.com` 收银台埋点 | 未在真实支付中验证，可能影响小米支付收银台 |
| `payment-sdk.logs` | 支付宝 `/loggw/logUpload.do`、微信支付 `/mmpayendpointreport/` | 未在真实支付中验证，可能影响第三方支付 |
| `location.uplink` | `itsdata.map.baidu.com/long-conn-gps/`、`aloc-gnss-eph` 热点网格、`/lbs_sdkcc/report` | 未在真机上验证，可能影响查找设备与网络定位 |
| `ct-autoreg.http` | `zzhc.vnet.cn`、`typdm.tydevice.com` 电信自注册 | 电信入网登记失败 |
| `ct-autoreg.sms` | `SmsManager.send*`（仅 `com.xiaomi.registration` 进程内） | 电信入网登记失败 |
| `simactivate.activation-receipt` | `ac.account.xiaomi.com/pass/activation/report` | 可能影响 SIM 卡激活 |

### 高风险默认阻止

通讯录、短信原文、应用列表、设备指纹、短信链接这类数据一旦泄露，后果比丢失一个辅助功能严重得多。因此下面 10 条规则（规则模型中的 `highRisk`）虽然会带来副作用，仍然默认开启，副作用照常写在规则的「影响」里，不喜欢的可以逐条关闭。分类菜单的「全部开启」会一并开启它们，仍跳过上表中默认关闭的规则。

| 规则 | 拦截内容 | 影响 |
|---|---|---|
| `securitycenter.traffic-sms` | 流量纠错时上传运营商短信原文、Android ID 与归属地 | 无法提交流量纠错 |
| `sogou-ime.contacts` | 搜狗输入法上传通讯录 | 无法生成云端通讯录词库 |
| `assistant.app-sync` | 负一屏 `store.assistant.miui.com/component/store/syncAppInfo` 全量应用列表 | 新装应用的小部件推荐可能缺失 |
| `guard.app-detect` | `flash.sec.miui.com/detect/app` 应用云检测上传的已装应用列表 | 云端应用风险识别不可用 |
| `securitycenter.url-detect` | `url-detect.engine.mi.com/url/detect` 链接云检测上传的短信与网页链接（可能带个人令牌） | 云端链接检测不可用 |
| `securitycenter.promo-cards` | 安全中心 `/info/layout` 运营卡片（省电建议请求会附带已安装包名） | 扫描结果页推荐卡片与省电建议不再显示 |
| `vipaccount.usage-upload` | 社区 `/user/batchuploadappdata` 使用记录 | 依赖使用记录的成长任务无法完成 |
| `sofire.domains` | `sofire.baidu.com` 设备指纹 | 输入法内百度账号登录的风控校验可能失败 |
| `baidu-ime.ubc` | `tcbox` / `h2tcbox` / `mbd.baidu.com` 的 `/ztbox` | `/ztbox` 用 `action=` 参数复用多种服务，而匹配器忽略查询串，无法只拦 `action=zubc`，其他百度云端功能可能受影响 |
| `systemhelper.firewall` | 联通终端管理整体断网（`dm.wo.com.cn` 登记，上报 IMEI / IMSI / 基站 / Wi-Fi / 位置） | 联通终端登记将失败 |

## 3. 数据类型 × 通道覆盖矩阵

| 数据类型 | 主要采集方（证据） | 上报通道 | 覆盖规则 | 未覆盖部分 |
|---|---|---|---|---|
| 设备标识（OAID / VAID / UDID、指纹） | IdProvider（安全中心）；qimei、sofire、个推 IDO / OneID、移动安全联盟 UAID | ID 服务提供方；`snowflake.qq.com`、`sofire.baidu.com`、`ido.gepush.com`、`zxid-m.mobileservice.cn`；也随埋点附带 | `idprovider.identifiers`、`qimei.domains`、`sofire.domains`、`getui.collect`、`uaid.report`、`telecom-auth.report`、`misight.trial-check`、`simactivate.firewall`*、`simactivate.activation-receipt`*、`systemhelper.firewall`、`ct-autoreg.http`*、`ct-autoreg.sms`*，以及全部埋点规则 | Android ID、IMEI 哈希仍可直接读取并夹带在服务请求中（如红包助手、流量纠错） |
| 应用列表 / 应用活动 | OneTrack `/track/appUpload`（20+ 应用）、应用商店激活统计、服务框架激活统计、负一屏 `syncAppInfo`、MQS `appLifeUpload` / `killProcessUpload`、搜狗 `appinfo.gif` / `mpkg.gif` | 见左 | `onetrack.domains`、`onetrack.pubsub`、`market.app-active`、`xmsf.app-active`、`assistant.app-sync`、`mqs.domains`、`sogou-ime.pingback`、`vipaccount.usage-upload`、`securitycenter.promo-cards`、`securitycenter.cloud-scan`*、`guard.apk-collect`、`guard.app-detect` | 服务请求中夹带的包名，例如应用商店更新检查需要上传已安装应用版本，属于服务 |
| 使用行为事件 | OneTrack（98 个应用）、小米分析、MiStat、小爱埋点、神策、灯塔、字节 AppLog、各应用自有埋点 | 见各规则 | 37 条 ANALYTICS 规则，外加 `onetrack.method` 在进程内拦截 `track*` 调用 | 推送长连接内的通知点击 / 到达统计与推送数据走同一条连接，无法单独拦截；桌面、相册中原生 Rust 实现的 OneTrack 上传器可能绕过进程内钩子（见残余风险） |
| 位置 / Wi-Fi / 基站 | 系统分析服务（AppOps 显示近一小时内读取精确位置与 Wi-Fi 扫描）、metoknlp、百度定位助手、高德 / 腾讯 / 百度定位 SDK | `cgicol.amap.com` 众包采集、`rttgpsreport.map.qq.com`、`itsdata.map.baidu.com` 轨迹长连接、`/statloc` 等统计 | `analytics.firewall`、`location.collection`、`location.sdk-stats`，以及默认关闭的 `location.uplink`（`itsdata.map.baidu.com/long-conn-gps/`、热点网格） | 定位查询请求本身会携带 Wi-Fi / 基站信息（网络定位的原理所在），属于服务，未拦截 |
| 通讯录 / 短信 / 通话记录 | 搜狗通讯录词库；流量纠错短信；安全中心链接云检测上传的短信链接；短信 `mixin.xiaomi.net/report/v2/postsms` | 见左 | `sogou-ime.contacts`、`securitycenter.traffic-sms`、`securitycenter.url-detect` | `mixin` 上传短信的触发条件未能确认（待确认 3） |
| 剪贴板 | 小爱、负一屏扩展、服务框架、AI 识屏等 9 个应用持有后台读剪贴板权限 | 未发现专门上报端点 | 无专门规则；内容若经埋点上报，由埋点规则覆盖 | 无法静态证明剪贴板内容不会夹带在服务请求中 |
| 输入内容（输入法） | 百度、搜狗、讯飞输入法 | 输入行为统计、pingback、统计 SDK；云输入 / 语音属于服务 | `baidu-ime.stats`、`baidu.mobstat`、`baidu-ime.ubc`、`baidu.ads`、`sogou-ime.pingback`、`sogou-ime.alive`、`iflytek-ime.stats`、`ime.promo`、`carrier-auth.sdk-logs`、`gdt.domains`、`qimei.domains`、`rmonitor.domains`、`bugly.domains`、`tencent.rdelivery-report` | 云输入请求本身包含正在输入的文字，属于服务；关闭云输入只能在输入法设置中操作 |
| 屏幕内容 | 内容识别（`contentcatcher`，uid 1000）、传送门（`contentextension`）、AI 识屏（`aicr`） | 用户触发时的识别请求属于服务；AI 识屏运行时出现 `log-sdk.clickwifi.net` | `aicr.runtime-log`、OneTrack | 识别请求本身会上传屏幕文字，属于服务 |
| 照片 / 人脸 | 相册云同步、人脸云端聚类 | `galleryfaceapi.micloud.xiaomi.net`（云同步） | 不拦截（用户开启的云服务） | — |
| 音频 | 小爱、语音唤醒 | 语音识别属于服务；`pusherapi-iotdcm.xiaomixiaoai.com/.../uploadlog` 只见静态字符串 | `xiaoai.tracker`、`voicetrigger.log-upload` | — |
| 日志 / 崩溃 / 性能 | MQS（电量与性能）、MiSight、服务框架远程取日志、Sentry、Bugly、RMonitor、APM+、Crashlytics、RUM | 见各规则 | 26 条 DIAGNOSTICS 规则 | uid 1000 原生守护进程的上传（见残余风险） |
| 硬件信息 | MQS `hardwareInfoUpload` / `hwInfoUpload` / `emmcLifeUpload` / `sensorUpload` | `mqs-log.miui.com` | `mqs.domains`、`mqs.port-upload` | — |
| 广告 | 系统广告服务、小米广告 SDK（`api.ad.xiaomi.com` 出现在 105 个应用中，OneTrack 内置广告监测 URL 处理）、广点通、穿山甲、百度联盟、腾讯音乐广告、AdMob、应用双开广告开关 | 见各规则 | 11 条 ADS 规则（天气广告默认关闭） | 由服务端在普通内容接口中混排的推广条目无法区分 |

\* 默认关闭。

## 4. 重要发现

1. **关闭「用户体验计划」后上报照常进行。** 设备上 `upload_log_pref=0`、`allow_app_track=0`、`personalized_ad_enabled=0`，但空闲抓包中系统分析服务仍连接 `tracking.miui.com`、`sdkconfig.xiaomi.com`，并连接第三方 `open.e.kuaishou.com`（快手广告）与 `unet.quark.cn`。同一时段 AppOps 记录它以前台服务身份读取了精确位置并触发 Wi-Fi 扫描。系统开关不能作为隐私边界。
2. **OneTrack 刻意隐藏上报主机。** 102 个应用把 `tracking.*.miui.com`、68 个应用把 `sdkconfig.*.xiaomi.com` 以字节数组常量拼接，明文扫描查不到。另有备用域名 `pn-tracking-bak.onetrack.mi.com`、`pn-sdkconfig-bak.onetrack.mi.com`。只拦明文域名会漏掉大部分应用，因此规则按解码结果覆盖全部地区变体与备用域名。
3. **OneTrack 还有一条原生的 Google Pub/Sub 上报通道。** 桌面（`com.miui.home`）的原生库中有 `pubsub.googleapis.com/api/v1/token` 与 `hyper_one_track::pubsub::uploader` / `upload_ps_data`；相册中有 `hyper_common/one_track/src/pubsub/secret_key.rs` 与内嵌的 RSA 公钥。这说明 OneTrack 存在 Rust 实现，可直接把事件发往 Google Cloud Pub/Sub，不经过 `tracking.miui.com`。另有 17 个小米应用把 `https://pubsub.googleapis.com` 与 `tracking.*`、`sdkconfig.*` 一起以字节数组隐藏。`onetrack.pubsub` 只对这些小米包拦截该主机，从不作用于 GMS、Play 等谷歌应用。原生 Rust 代码若自行解析 DNS 并使用自带 TLS，进程内钩子拦不到，需要用诊断模式与抓包复核。
4. **安全中心一个 uid 下混着服务和上报。** `data.sec.miui.com` 同时承载隐私同意服务（`/privacy/agree|get|revoke`，必须放行）、月度统计（`/data/upload`）、充电统计（`/data/chargeV2`）和广告回传（`/adv/tail`、`/adv/game_ad/report`）；`api.sec.miui.com` 同时承载骚扰拦截、地震预警和流量纠错。纠错请求会上传运营商短信原文、Android ID 与省市。安全中心运行在 uid 1000，不能断网，只能按路径拦截。
5. **电量与性能通过 MQS 收集应用活动。** `mqs-log.miui.com/data/` 下有 15 个上传接口，涵盖应用生命周期、杀进程、亮屏、传感器、硬件、存储寿命、冷启动等；`port.sec.miui.com/mqsas/` 下有售后、温控等文件上传。同一主机上的固件检查 `/mqsas/ffu/checkUpgrade` 是服务，予以保留。
6. **小米服务框架可由服务端远程提取日志。** 推送服务端下发指令后，`FetchHelper` 收集并压缩本地日志，上传到 `upload-data-api.xmpush.xiaomi.net`。该主机只做上传，可以整域拦截，不影响推送。服务框架另经 `api.xmpush.xiaomi.com/xmsf/stats/app/active` 上报应用激活记录。
7. **负一屏会上传完整的已安装应用列表。** `component/store/syncAppInfo` 的请求体是全部已安装包的包名、版本号与首次安装时间，并在每次安装、卸载、升级时增量上报。应用商店的 `statistics/appactive` 上报每个应用的安装来源、激活时间与 APK 哈希。
8. **输入法携带多套第三方统计。** 搜狗：15 个 pingback / kpi / breaklog / newalivelog 主机，以及腾讯 qimei（`snowflake.qq.com`）、RMonitor、Bugly、灯塔、RDelivery，打字时均有实际连接。讯飞：自带 statssdk，向 `inputd` / `inputl1` / `inputl2.xfinfr.com` 与 `log.voicecloud.cn` 上报，打字时 `inputd` 出现 5 次。百度：`/v5/fb/*` 输入行为日志、UBC（`tcbox.baidu.com`）、移动统计（`hmma.baidu.com`），以及 sofire 设备指纹（`sofire.baidu.com`）。三家都从 `adv.sec.miui.com/info/icon` 拉取推广图标。
9. **小米应用内嵌第三方推送与数据 SDK。** 米家同时内嵌个推（含 IDO、GTC、OneID 数据采集）、极光（JAnalytics / JOperate）、字节 DataRangers / APM+、华为 HMS 统计、移动安全联盟 UAID。抓包可见 `ido.gepush.com`、`cdn-sdk-opt.getui.com/tdata_*`。小米商城另向 `beats.be.mi.com`、`a.hl.mi.com`、`tk.d.mi.com`、`t.hl.mifile.cn` 四套埋点上报，启动后这四个主机累计连接 56 次。
10. **存在运行时才出现的通道。** AI 识屏（`com.xiaomi.aicr`）连接了 `log-sdk.clickwifi.net`、`api-sdk.clickwifi.net`、`cache-files.isjike.com`，但这些主机在其全部 dex、so 与内置配置中均不存在，说明执行的是运行时下发的代码。仅凭静态扫描必然会漏掉这类通道。
11. **路径名会误导，必须追到代码。** 游戏服务 SDK 中的 `/gradelive/appuploadEn`、`/v3/sdk/appUploadGen` 看似上传应用列表，实际是腾讯云人脸核身上传活体视频（实名认证服务），因此未拦截。短信 `client.sms.miui.com/api/client/normal/report` 是用户点击「举报」后才发送的，同样不拦截。游戏加速的 `game.toolbox.miui.com/toolbox/network/report` 实际是「专用网络加速」的激活与时长接口，其响应会被功能直接使用，也不拦截。反过来，同一主机上的 `richMsg/aimMsgShowRpt` 是自动发送的卡片曝光统计，予以拦截。

## 5. 刻意不拦截的厂商服务

| 服务 | 端点（示例） | 不拦截的原因 |
|---|---|---|
| 账号 | `account.xiaomi.com`、`api.account.xiaomi.com`、`cnac.account.xiaomi.com` | 登录与鉴权 |
| 推送 | `api.xmpush.xiaomi.com`（`/xmsf/stats/` 除外）、`resolver.msg.xiaomi.net`、`*.register.xmpush.xiaomi.com` | 推送注册与下发；`cn.register.xmpush` 在 19 个应用中以字节数组隐藏，但它是注册端点 |
| 云服务 | `*.micloud.xiaomi.net`（含 `galleryfaceapi`、`noteapi`、`find.api`、`deviceapi`） | 云同步、查找设备 |
| 更新与商店 | `update.miui.com`、`app.market.xiaomi.com`（`/apm/statistics/` 除外）、`updateinfo.market.xiaomi.com`、`api.developer.xiaomi.com/autoupdate` | 系统更新、商店浏览 / 下载 / 更新检查 |
| 云配置 | `mcc.inf.miui.com`、`jupiter.sys.miui.com`、`cdn.exp.xiaomi.com`、`abtest.mistat.*`、`*.rconfig.qq.com`、`rdelivery.qq.com/v{1,3}/config/pull`、`planconfig` / `abtest.openspeech.cn` | 拉取配置，不是上报 |
| 安全检测与应急 | 病毒库更新 `update.avlyun.sec.miui.com`；云查杀与云端检测主机 `check.engine.miui.com` 与 `fraud-detection.engine.miui.com` 云端检测子域；`virus-engine.sec.miui.com` 的云查杀（仅由默认关闭的 `securitycenter.cloud-scan` 管辖，默认放行）；AI 检测、NFC 名单；`srv.sec.miui.com/earthquake/warning/*`、`car-srv.sec.miui.com/car/SOS/*`、`eps.caict.ac.cn` 与 `gateway-papa.cloud.weizhi.com`（一键报警） | 这些是安全功能本身所需的服务端点：病毒库、云查杀、AI 检测、NFC 名单、地震预警、紧急求助。**默认阻止的安全类上传通道只有以下几条，均为高风险或已写明影响：** 应用云检测上传已装应用列表（`guard.app-detect`）、链接云检测上传短信与网页链接（`securitycenter.url-detect`）、本地查杀结果上报（`guard.scan-result-report`）、完整 APK 上传（`guard.apk-collect`）；详见第 9 节 |
| 隐私同意 | `data.sec.miui.com/privacy/*`、`privacy.api.intl.miui.com/collect/privacy/*`、`ads.privacy.qq.com/optout` | 同意与撤回本身就是隐私控制，拦截反而会让撤回失效 |
| 定位 / 天气 / 地图 | `apilocate.amap.com`、`lbs.map.qq.com`、`loc.map.baidu.com`（`/statloc` 除外）、`weatherapi.market.xiaomi.com` | 网络定位与天气 |
| 输入 / 语音 | `ws-keyboard.shouji.sogou.com`、`imeclient.openspeech.cn`、`*.xfyun.cn`、`speech.xiaomixiaoai.com` | 云输入与语音识别 |
| 支付 / 钱包 | `tsmapi.pay.xiaomi.com`、`mipay.com`、`sdk-open-phone.getui.com`（一键登录）、`kycwa.tencentcloudapi.com`（实名核身） | 支付、公交卡、实名认证 |
| 家长控制 | 家人守护 `/admin/*`、`/ward/*` 使用时长接口 | 上传使用时长就是该功能本身，由用户开启 |
| 客服 / 远程诊断 | `chat.kefu.mi.com/openapi/misight/diagnostic/*` | 用户发起、需验证码 |
| 游戏加速 | `game.toolbox.miui.com/toolbox/network/report`、`adv.sec.miui.com/gameTurbo/*`、`/info/gameConfig` | 专用网络加速激活与游戏加速配置 |
| 其他第三方设备注册 | `ichannel.snssdk.com`（字节设备注册，抖音支付依赖）、`*.cmpassport.com` / `open.e.189.cn` 登录接口、`zxid-m.mobileservice.cn/sdk/uaid/reportAuthToken` | 支付与一键登录所需 |

## 6. 残余风险与诊断模式

1. **原生 OneTrack 上传器。** 桌面与相册中的 Rust OneTrack 经 `pubsub.googleapis.com` 上报。两者都有自己的 uid，但承担核心功能，不能断网；`onetrack.pubsub` 只在 Java DNS / Conscrypt 路径上生效。若原生端使用自带解析与 rustls，该规则会失效，需要靠诊断模式（看不到原生连接）之外的抓包来确认。
2. **uid 1000 原生进程。** 约 50 个系统包共用 uid 1000，无法断网，只能注入其中的 Java 应用进程。纯原生守护进程（如 mqsas / MiSight 的原生端、`com.bsp.catchlog`）若直接用原生套接字上报，域名与 URL 规则都管不到。抓包中 uid 1000 组的连接只能归到整组，无法确定到包。
3. **原生网络栈与直连 IP。** 规则在 Java DNS、Conscrypt TLS SNI 与 OkHttp / HttpURLConnection / Cronet 层生效。自带 TLS 或使用 QUIC 的原生库可以绕过，例如腾讯 WNS、音乐播放器中发往裸 IP 的 `POST /`、下载管理器中的 `res.res.res.res:80 POST /`。整体断网只适用于自有 uid 的纯上报应用。
4. **加密或运行时下发的端点。** 极光 SDK 的主机名经过加密；AI 识屏会下发新代码；讯飞的 `ossp.voicecloud.cn/ossp/do.aspx` 以命令参数区分功能，配置与日志混在一起，无法按路径区分。
5. **推送长连接内的统计。** 小米推送在同一条长连接上传输通知到达、点击与 `NotificationListener` 交互统计，拦截会断掉推送。
6. **服务请求夹带数据。** 应用商店更新检查需要已安装版本列表；网络定位需要 Wi-Fi / 基站列表；云输入需要上传正在输入的文字；屏幕识别需要上传屏幕文字。这些是服务的必要输入，本模块不处理，只能由用户关闭对应功能。
7. **覆盖面依赖抓包场景。** 3 次抓包没有覆盖夜间维护窗口、充电中、首次开机向导、OTA 后首启等场景，这些场景下的上报只能靠静态证据推断。

**诊断模式**用于弥补以上缺口：开启后，被注入进程中的 DNS、TLS 与 HTTP 钩子在原有拦截之外，按「应用 · 主机 · 路径前缀」记录每次外连及其是否被拦截，并在设置页列出，最新的排在前面。建议做法：

- 在上述未抓包场景（夜间充电、OTA 后首启、首次开机向导）各开一次诊断模式，把新出现的上报主机提交为规则候选。
- 定期核对 OneTrack、广告 SDK 等跨应用规则的「未拦截」记录。若出现新的地区变体或备用域名，说明需要更新规则。
- 诊断模式只能看到被注入的进程。系统分析服务、系统广告服务这类整体断网的目标不注入，原生守护进程也看不到，后者仍需定期抓包复核。

## 7. 待确认事项

1. 4 条仍默认关闭的「有风险」规则（`weather.ads`、`payment.checkout-track`、`payment-sdk.logs`、`location.uplink`）需要在真机上逐一验证：天气生活指数、小米支付与第三方支付下单、查找设备与网络定位。验证通过的可改为默认开启。另有 `sofire.domains`、`baidu-ime.ubc`、`assistant.app-sync` 按高风险策略默认开启，仍需在真机上确认实际影响范围：百度账号登录、百度输入法云功能、小部件推荐。`simactivate.tee-report` 同样待验证：`tz.sec.xiaomi.com/session` 是否在 TEE 不可用时充当 Passport 回退（即拦截后小米账号登录是否受影响），验证前保持默认关闭。
2. 高德定位 SDK 的 `adiu.amap.com`（设备 ID）与 `apiinit.amap.com/v3/log/init`（含鉴权初始化）未拦截。拦截后网络定位是否仍正常，需要在真机上验证。
3. 短信 `mixin.xiaomi.net/report/v2/postsms`（携带 SIM 服务令牌上传短信）的触发条件与用途未能确认。
4. `com.xiaomi.aireco`、`com.miui.miservice` 的 dex 中没有任何上报证据（可能是拆分包或加固），需要补充反编译。
5. `miclaw-ecology.developer.miui.com` 被服务框架、小爱、账号频繁访问（每次抓包 4–8 次），`screentime.comm.miui.com`、`metok.sys.miui.com`、`predownload-admin.g.mi.com` 用途不明，暂未拦截。
6. 相机中出现小米内部域名 `service.mify.mioffice.cn/api/v1/files/upload`，未能确认正式版是否会调用。
7. `statres.quickapp.cn`（音乐播放器有流量）、`marketingplatform.google.com`（国际版浏览器有流量）、`sdkrp.kookong.com`（万能遥控）只有主机名，看不出是否用于上报，暂未拦截。

## 8. 评审处理记录

独立对抗评审提出 26 项修改。处理结果如下（下文提到的「默认关闭」是当时的结论；其中高风险的几条后来按第 2 节的策略改为默认开启，影响照旧显示）：

- **采纳**：1–6、8、10–12、14、15、17–21、23–26 及 16 的一部分（`miui.systemui.plugin` 加入 `onetrack.domains`、`onetrack.method`、`xiaomi-ads.domains`）。
- **部分采纳 / 修正**：
  - 7：删除 `tcbox` / `h2tcbox` 整域拦截与 `baidu-ime.stats` 中的 `mbd.baidu.com/ztbox`，三者的 `/ztbox` 改为单独的默认关闭规则 `baidu-ime.ubc`，因为匹配器不看查询串，无法限定 `action=zubc`。
  - 9：删除 `/game/xunYouReport`。新增 `game.toolbox.miui.com/toolbox/network/report` 的建议被驳回：反编译显示它是「专用网络加速」的 `updateActiveTime` 接口，响应被功能直接使用。
  - 14、15：评审称原规则因主机不符而漏拦，这一判断不成立。`assistant.miui.com` 与 `vip.miui.com` 按点后缀匹配，本来就覆盖 `store.assistant.miui.com` 与 `api.vip.miui.com`（流量中均已确认）。仍按建议收窄到精确主机，`assistant.app-sync` 改为默认关闭。
  - 22：采纳 `ams-howl` APM 路径，并入 `globalbrowser.diagnostics`。删除 `dispatch-api-online.vemarsdev.com` 的建议被驳回：该端点在路径扫描中有证据（浏览器 `/monitor/collect/c/performance/`），只是未出现在 evidence.md。
  - 8：评审称 `databyterangers.com.cn` 无证据，这一判断有误，路径扫描中有 `/simulator/mobile/log`。但该路径属于 DataRangers 圈选调试模式，非生产上报，删除不影响覆盖。
  - 26(d)：`marketingplatform.google.com` 是营销网站而非上报端点，未加入。26(f)：`statres.quickapp.cn` 无路径证据、名称含义不明，未加入。
  - 拼凑路径修正：`xunlei.stats` 中 `uploadlog.xlmc.sandai.net` 的 `/upload` 路径是推测值，已改为把该主机放入 `thirdparty.sdk-logs` 整域拦截（主机名即表明仅用于日志上传）。
- **驳回**：
  - 13：`idprovider.identifiers` 保持默认开启。它只对非系统调用方返回 MSA 标准的全零退出值，小米系统应用仍拿到真实值。
  - 16 其余部分：方法钩子只置空返回 void 或基本类型的方法，并返回对应类型的默认值，不会对基本类型返回 null，因此无需把 `track*` 改为显式列表。

## 9. 运营商与安全检测组件

运营商终端管理、SIM 激活与安全检测组件各自有独立的上报通道，也各自混有必须保留的服务，按下面的口径处理。

| 组件 | 上报内容与触发 | 处理 |
|---|---|---|
| 联通终端管理（`com.mobiletools.systemhelper`，SystemHelper） | 中国联通 CU-DMS：向 `dm.wo.com.cn:18081/register`（备用 http `:18080`）上报双卡 IMEI / IMSI / ICCID、基站 TAC / CID、Wi-Fi BSSID、已配对蓝牙 MAC、IPv6、应用使用记录与经纬度信号日志。触发条件为 Wi-Fi 或联通数据，空闲时每小时经内嵌的百度定位 SDK 读取一次位置 | 独占 uid，整体断网（`systemhelper.firewall`，高风险，默认开启；联通终端登记将失败） |
| 电信自注册（`com.xiaomi.registration`） | 中国电信终端自注册：开机、换卡、每 15 天向 `zzhc.vnet.cn` POST IMEI / MEID / IMSI / ICCID 等；仅 Wi-Fi 版本走 `typdm.tydevice.com`；另向 10659401 发送数据短信（型号、双 IMEI、IMSI、软件版本）。共用 `android.uid.phone`，无法断网 | `ct-autoreg.http` 按域名、`ct-autoreg.sms` 在该进程内置空 `SmsManager.send*`，均默认关闭（入网登记会失败）；该进程独立，不影响用户自己的短信 |
| 中国移动 DM | 本机未内置 | 无需处理 |
| SIM 激活服务（`com.xiaomi.simactivate.service`） | 绝大部分是小米账号与 SIM 绑定（`/pass/activation/activate`、`activationReport` 等）；另有 TEE 校验失败时向 `tz.sec.xiaomi.com/session` 上报 OAID、设备 ID 与 CPU ID，以及激活后向 `/pass/activation/report` 回传哈希后的 SIM / 设备标识、手机号与加密短信 | 保留绑定；`simactivate.tee-report` 默认关闭（`/session` 可能是 TEE 不可用时的 Passport 回退，未证实，可能影响小米账号登录）；`simactivate.activation-receipt` 默认关闭；前缀 `/pass/activation/report` 不会命中 `/pass/activation/activationReport` |
| 手机管家病毒引擎（`com.miui.guardprovider`） | 保留：病毒库更新、云查杀、AI 检测、推送。拦截：本地查杀结果上报 `virus-engine.sec.miui.com/virus/localResult/`（`guard.scan-result-report`）；服务端下发 `collect_package` 后上传完整 APK 的 `CollectPackagesAction`（`guard.apk-collect`，主机由服务端指定，只能在方法层拦截，返回 null，分发器会捕获并上报错误） | 默认开启 |
| 同上，应用云检测 | `flash.sec.miui.com/detect/app` 上传全部非系统应用的包名、版本、签名 MD5 与应用名 | `guard.app-detect`，高风险，默认开启（云端应用风险识别不可用） |
| 安全中心的云端检测 | 保留：NFC 名单及其余云端检测端点。链接云检测会把短信与网页里的每个链接（url、时间戳、`c=2`、`sign`）POST 到 `url-detect.engine.mi.com/url/detect`，链接可能带个人令牌。其 OneTrack 统计已由既有规则覆盖 | `securitycenter.url-detect`，高风险，默认开启（云端链接检测不可用） |

本节共 9 条规则：默认开启 5 条（`systemhelper.firewall`、`guard.scan-result-report`、`guard.apk-collect`、`guard.app-detect`、`securitycenter.url-detect`，其中第 1、4、5 条为高风险），默认关闭 4 条（`ct-autoreg.http`、`ct-autoreg.sms`、`simactivate.activation-receipt`、`simactivate.tee-report`）。`securitycenter.cloud-scan` 已整域拦截 `virus-engine.sec.miui.com`，与 `guard.scan-result-report` 重叠；前者关闭时，后者仍只拦本地结果上报。
