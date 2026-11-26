# HyperPGuard 代码质量审计与自研决策

- 审计对象：[vivibibi30/hyperpguard](https://github.com/vivibibi30/hyperpguard) v2.0.4（`0b32341`，2026-11-26）
- 规模：Java 8,424 行 / 25 个文件；`MainActivity.java` 2,531 行，`BottomBar.java` 1,027 行
- 许可证：GPL-3.0
- 方式：静态审阅（未在真机运行）。行号均基于上述提交。

## 结论

**建议独立实现，不 fork、不直接使用。** 上游的价值在于「HyperOS 各组件在采集什么、从哪个域名上报」这份调研成果（`docs/RULES.md`、`Config.DEFAULTS`），而不是代码本身。

理由：

1. 架构底座有缺陷：配置通道、签名身份、网络拦截层都需要推倒重来，修补成本高于重写。
2. 默认值激进且与自身文档矛盾，装上即改变多个系统组件的行为。
3. 存在已确认的功能性 bug（拦截根本没挂上、递归、超时失效、导入损坏）。
4. 约一半代码是界面层（4,304 / 8,424 行），其中自绘底栏与开关约 1,200 行，与隐私功能无关，却占维护成本大头。
5. GPL-3.0：复制其代码即须整体以 GPL-3.0 发布。独立实现时只参考域名与行为清单（事实信息），不复制实现。

## 已确认的问题

### 严重

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| 1 | 配置写入 `Settings.Global` | `Config.java:481-482` 用 `su settings put global hyperpguard_conf …` | 任意应用无需权限即可读取，可据此检测模块存在、读出全部开关与自定义规则。隐私工具本身成为指纹源。libxposed API 100+ 已提供 `RemotePreferences`，无需 root、不外泄 |
| 2 | 签名私钥入库 | `debug.keystore`（口令 `android`）提交在仓库，CI 用它签发正式 Release（`build.sh:32-56, 92`） | 任何人可签出同签名 APK 覆盖安装，继承 KernelSU 按 uid 授予的 root 与 LSPosed 作用域（21 个应用，含系统组件与输入法） |
| 3 | 默认值与文档矛盾 | `RULES.md:46-50` 写明 trustservice「默认关闭（影响远程认证、查找设备）」；实际 `K_G_TRUST` 默认 `true`（`Config.java:234`），且 `HookEntry.java:415` 无条件拦截 `deviceapi.micloud.xiaomi.net` | 默认安装即可能影响设备认证与查找设备 |

### 高

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| 4 | 短信拦截从未生效 | `PrivacyApis.java:94-102` 三个方法签名均写错（如 `sendTextMessage` 写成 4×String+1×PendingIntent，AOSP 为 3×String+2×PendingIntent；`List` 应为 `ArrayList`） | `getDeclaredMethod` 抛异常后只在日志记一条 `hook miss`，用户无从得知 |
| 5 | IPv6 字面量导致递归 | `NetGuard.java:227-228` 的 `parseIpLiteral` 调用已被自身 hook 的 `InetAddress.getByName` | 严格模式（analytics 默认开启）下遇到 `::1`、`fe80::` 等会反复重入直至 `StackOverflowError`，本地 IPv6 被误拦 |
| 6 | 网络拦截仅在 Java 层 | 只 hook `URL.openConnection()`（无参）、`InetAddress`、`Socket`、`okhttp3.Dns`（`NetGuard.java:44-136`） | Cronet / native 网络库直接绕过；路径规则只在 `openConnection()` 生效，OkHttp 请求的路径规则全部失效 |
| 7 | root 超时失效 | `Shell.java:86-98` 先阻塞读 stdout 到 EOF，再 `waitFor(20s)` | su 授权弹窗未处理或命令卡住时线程永久阻塞，20 秒超时形同虚设 |
| 8 | 配置同步竞态 | 每次切开关新起线程执行 `su settings put`（`Config.java:480-483`），热重载固定 600ms 后触发（`MainActivity.java:2078`） | 线程乱序时旧快照可能最后写入导致开关丢失；su 慢于 600ms 时热重载读到旧配置 |
| 9 | 导入配置损坏自定义规则 | 导出原样读取 `Settings.Global`（规则已 URL 编码，`ConfigIO.java:56`），导入未解码直接写回（`ConfigIO.java:98-99`），`syncOut` 再编码一次 | 每导入一次，自定义规则多编码一层 |
| 10 | 热重载可能清空全部 hook | `HookEntry.java:71-83`：先卸载所有旧 hook，若 `currentApplication()` 为空则直接返回不重装 | `autoHotReload=true` 下，模块更新后部分进程保护静默消失 |

### 中

- **大范围启发式 hook**：按类名扫描 DEX 后批量替换方法。`ImHooks.voidPrefix` 把百度/讯飞统计包下除 `init`/`set`/`start` 等前缀外的所有非 native `void` 方法置空（`ImHooks.java:59-79`）；安装器 OAID 兜底会命中 OAID 相关类中任意 ≤2 参数的 `String` 方法（`HookEntry.java:692-707`）。误伤不可预测。
- **启动开销**：安全中心单次注入扫描最多 2×8,000 个类，安装器 3×2,500，analytics 最多 20,000（`DexFile.entries()` 已废弃）。
- **硬编码混淆名**：`XsofHooks.java:65` 直接调用混淆后的 AIDL 回调 `G2`，`c.d.a.b.f#a` 等，换个 ROM 版本即失效或错挂。
- **拦截回调不兜异常**：返回 `null` Cursor / VirtualDisplay / android_id，调用方空指针时会让系统应用崩溃。
- **共享可变返回值**：`getInstalledPackages` 等返回同一个 `ArrayList` 实例（`PrivacyApis.java:40-51`），调用方修改会互相污染；API 33+ 的 `PackageInfoFlags` 重载未覆盖。
- **SQL 子串匹配**：`CaptureHooks.java:30-39` 拦截任何包含表名子串的 `execSQL`，可能阻断数据库迁移。
- **自定义 `类名#*` 规则**：在系统进程中把整类方法替换为默认值，且规则来源是可被 `WRITE_SECURE_SETTINGS` 持有者篡改的全局设置。

### 工程

- 无 Gradle、无 XML 布局、无依赖锁定；CI 下载 build-tools 与 android.jar 均不校验哈希，android.jar 下载失败时回落到 API 16 的 Maven 桩包（`build.yml:34-76`）。
- `pull_request` 触发的 workflow 拥有 `contents: write`。
- 测试只覆盖 `Shell.q` 转义与日志格式化（`PureTest.java`），配置解析、规则匹配、hook 逻辑零覆盖。
- `MainActivity` 是 2,531 行的上帝类；`onDestroy` 不移除 Handler 回调与 XposedService 监听，后台线程销毁后仍 Toast。
- i18n 用 Java `HashMap` 以中文整句为键，约 30 处 Toast 未覆盖。
- 注释与实现不符：`Config.load` 注释称「查询本应用的只读 provider，不写系统设置」，实际读写 `Settings.Global`；类头注释称 hook 侧只用默认值。
- 仓库 2026-11-06 创建，已有 79 份版本说明（`1.0.1` → `2.0.4`），补丁版本密集，反映规则靠真机试错迭代。

## 做得好的部分（值得借鉴）

- 每个注入阶段独立 `try/catch`（`stage` / `netSafe`），单个 hook 失败不影响其他。
- 伪造值考虑兼容性：IMSI 保留真实 MCC+MNC，MAC 返回 `02:00:00:00:00:00` 而非 `null`。
- `RULES.md` 区分了「纯遥测」与「关闭会影响功能」的端点，是很有价值的调研资料。
- 用 `check_structure.py` 校验 `scope.list`、handler 表、UI 分组三者一致。
- 利用 API 102 热重载，改开关后无需重启目标进程。

## 自研方案要点

| 方面 | 上游 | 自研 |
|---|---|---|
| 配置通道 | `Settings.Global` + su | libxposed `RemotePreferences`（UI 写、hook 侧只读监听），不需要 root |
| 签名 | 私钥入库 | 私钥仅存 CI Secret |
| 网络拦截 | Java 层 hook | 首选按 uid 的系统级网络策略（netd 防火墙链 / `cmd connectivity`），覆盖 native；Java 层 hook 仅用于需要按路径拦截的场景（OkHttp `Interceptor` 链、`URL.openConnection` 全重载） |
| 方法拦截 | DEX 全量扫描 + 启发式 | 只用公开 SDK 入口（如 `com.xiaomi.onetrack.OneTrack`）与框架 API；按 ROM 版本声明式规则表，命中失败即上报「未生效」 |
| 默认值 | 激进全开 | 只默认开启「纯遥测、无功能影响」的规则；其余需用户确认 |
| 安全护栏 | 回调不兜底 | 每个拦截回调统一兜异常，失败时 `proceed()` 放行，宁可漏拦不崩溃 |
| 可验证性 | 界面显示开关状态 | 显示真实挂载结果（已生效 / 未生效 / 不适用），便于发现签名写错一类问题 |
| 构建 | 手写脚本 | Gradle + 版本锁定；规则匹配、配置解析、IP 判定写单元测试 |
| UI | 手写 Canvas | 标准组件（Compose 或 Preference），不做装饰性动效 |

### 首版范围（按价值/风险排序）

1. `com.miui.analytics` 整进程断网（纯遥测，风险最低）
2. OneTrack SDK `track*` 置空（公开入口，跨多个系统应用通用）
3. 运营商/厂商遥测域名（`typdm.tydevice.com`、`fxltsbl.com`、`dm.wo.com.cn` 等）
4. 系统广告：MSA 开屏、应用商店/安装器广告域名
5. 设备标识伪造（IMEI/Android ID/MAC/序列号），仅对作用域内应用
6. 输入法统计域名（只拦统计，不碰云输入）

### 不复刻

- trustservice MRM 与 `deviceapi.micloud`：影响设备认证与查找设备
- XSOF 混淆回调、`SQLiteOpenHelper` 启发式置空、`execSQL` 子串拦截
- 输入法 SDK 的 `void` 方法批量置空
- 任意 `类名#*` 自定义规则
- root 删除系统数据库（`fraud_detection_data.db`）
