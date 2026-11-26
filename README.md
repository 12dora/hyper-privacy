# HyperPrivacy

面向小米 HyperOS 的 LSPosed 模块：**只拦截隐私上报与遥测，不影响任何厂商服务**（应用商店、安全扫描、推送、云同步、查找设备、天气、系统更新等）。

## 工作原理

规则表（`core/.../rule/Catalog.kt`）中每条规则对应一个上报通道，并注明证据来源（抓包或 DEX）。规则通过三种机制生效：

| 机制 | 作用位置 | 说明 |
|---|---|---|
| 整应用断网 | system_server | 对纯遥测、独立 uid 的应用，通过系统防火墙链（`FIREWALL_CHAIN_OEM_DENY_3`）按 uid 拒绝联网，覆盖 native 网络栈。拒绝 uid < 10000 或共享 uid 的目标 |
| 域名拦截 | 目标进程 | 在 Java DNS 出口（`Inet6AddressImpl#lookupAllHostAddr`）与 TLS 握手（Conscrypt `startHandshake`，按 SNI）拦截指定域名及其子域，后者覆盖 HTTPDNS 直连 IP 的情况 |
| SDK 方法置空 | 目标进程 | 将公开 SDK 入口（如 OneTrack `track*`）替换为返回默认值；返回引用类型的方法默认跳过 |

设计约束：

- **失败放行**：所有拦截回调统一兜底异常，自身逻辑出错时调用原方法，宁可漏拦也不让宿主崩溃。
- **实时生效**：开关保存在 libxposed `RemotePreferences`，目标进程监听变更，无需重启或热重载。
- **可验证**：各进程上报每条规则的真实挂载结果与拦截次数，界面显示「已生效 / 未生效 / 未运行」。
- 作用域 `scope.list` 在构建时由规则表生成（需注入的应用 + `system`），不会与规则脱节。

## 环境要求

- HyperOS（Android 14+），推荐 Android 17
- LSPosed，支持 libxposed API 101 及以上

## 构建

需要 JDK 21 与 Android SDK（`platforms;android-37.2`、`build-tools;37.0.0`）。在 `local.properties` 中设置 `sdk.dir`，或设置 `ANDROID_HOME`。

```sh
./gradlew assembleDebug        # 调试包
./gradlew assembleRelease      # 发布包（未配置签名时输出未签名 APK）
```

发布签名只在 CI 中进行：推送 `v*` 标签后，Release 工作流从仓库 Secret 解码密钥并签名。仓库中不存放任何密钥。

## 质量门禁

```sh
./gradlew check                # detekt（含 ktlint 规则）+ Android Lint + 单元测试
scripts/privacy-scan.sh        # 检查已跟踪文件中是否含本机路径、主机名、内网 IP、邮箱等
```

- detekt：`config/detekt/detekt.yml`，在默认规则上收紧，任何问题即失败，无基线。本地可用 `./gradlew detekt -PdetektFix` 自动修正格式。
- Android Lint：警告视为错误，并检查依赖模块；仅在 `config/lint/lint.xml` 中保留有注释说明的抑制项。
- Kotlin：`allWarningsAsErrors = true`。

## 许可证

独立实现，未使用任何第三方模块的代码。

## 贡献者

<table>
  <tr>
    <td align="center"><img src="docs/images/contributors/opus.svg" width="72" height="72" alt="Opus 5.5"><br><b>Opus 5.5</b></td>
    <td align="center"><img src="docs/images/contributors/gemini.svg" width="72" height="72" alt="Gemini 4 Neon"><br><b>Gemini 4 Neon</b></td>
    <td align="center"><img src="docs/images/contributors/grok.svg" width="72" height="72" alt="Grok 4.6"><br><b>Grok 4.6</b></td>
  </tr>
</table>

<sub>头像图标来自 LobeHub Icons（MIT），商标归各自所有者。</sub>
