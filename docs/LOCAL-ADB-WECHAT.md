# 本机 ADB 与微信调试

## 当前功能

设置 → 手机权限 → 本机 ADB。页面显示连接状态、无线配对、微信文章和高级设置，
沿用原版 DSH SettingsPage、SettingsCard、DButton、字体与配色，并读取全局主题。
配对后可读取标题、公众号和当前 DOM 显示的阅读数；Agent 工具仍可列出页面。切回 DSH 不会关闭仍在微信任务中
打开的文章；若列表为空，需要先重新打开文章。

连接路径：DSH 普通应用 UID → 127.0.0.1:adbd → ADB localabstract → 微信 XWeb → CDP。
不依赖 Shizuku 服务，不调用 `adb shell curl`，也不使用电脑端口转发。程序不会自动
开启系统调试、修改 SELinux 或重启 adbd。15555 是 Shizuku+ shell 代理，不适用。

支持传统 TCP ADB 和 Android 11+ 无线调试的配对码配对。无线模式使用 TLS 1.3、
TLS exporter 绑定的 SPAKE2 握手与 AES-GCM 身份交换，配对后复用同一 RSA 密钥作为
TLS 客户端身份。无线模式强制要求 STLS，禁止静默退回普通 TCP。

### 无线配对步骤

1. 点击“开始无线配对”，阅读系统风格引导弹窗，选择“开始并打开设置”。
2. 首次使用允许系统通知权限；若通知或配对通知类别被关闭，会引导到系统通知设置。
3. 在开发者选项中开启“无线调试”，选择“使用配对码配对设备”，保持系统弹窗开启。
4. 下拉通知栏，在 DSH 的“请输入配对码”通知中点击“输入配对码”，输入六位数字并发送。
5. DSH 自动发现本机配对与连接端口，完成配对并验证 TLS，成功后通知显示“已连接”。
6. 返回 DSH，打开微信文章后点击“读取当前文章”。整个产品流程无需电脑或分屏。

通知使用 Android framework Notification / RemoteInput，由各 ROM 的系统通知栏渲染；
权限由系统权限管理器请求，引导弹窗使用 DeviceDefault 并跟随系统深浅色。
应用内高级设置使用 DSH 的 DDialog，可手动更新连接端口或选择传统 TCP 模式。

所有连接均使用 127.0.0.1。配对码仅在通知回复与当次握手期间存在，不写日志、报告或 preferences；仅保留加密 RSA 身份与
配对完成标记。连接端口可能在系统无线调试重新开启后变化，需要更新；系统撤销配对后
需重新配对。此处的“无线配对”不表示永久有效或无限次免授权。
ADB 断开或重启后需恢复对应系统调试条件。禁止自动读取其他应用的 ADB 私钥。

## Agent 工具

- `wechat_debug_status`：返回配置和实际连接状态；停用状态不发起连接。
- `wechat_debug_targets`：发现微信进程的调试 socket，列出文章 target ID，过滤模板。
- `wechat_article_read(targetId?)`：读取指定文章，未指定则读取第一个可用文章。

这些工具属于 Browser 能力组，沿用 CapabilityHub 的模式、自动化工具白名单、超时
和取消机制；不要求 Shizuku。开启 Browser 能力后，可在 DSH 对话中要求“读取当前
微信公众号文章的标题、公众号和阅读数”。工具由现有 plugin-device 动态注册。

仅返回显示值，例如“10万+”，字段缺失为 null，不将缺失值写成 0。
阅读数只从可见的 `js_read_area*` 阅读区提取，忽略正文中的数字和隐藏的旧计数。
单篇读取可提取已加载留言与页面提供的评论计数。批量扫描、导航和数据库热榜见
[公众号采集与热榜](WECHAT-COLLECTION.md)。单篇接口不主动刷新页面；成功结果覆盖保存到 `noBackupFilesDir/wechat/latest.json`，
失败不覆盖上次成功结果。检查 `collected_at_ms` 判断新旧。

ADB 私钥使用 DSH SecretStore 加密保存在 noBackupFilesDir，配置信息单独存放于
local-adb preferences；不返回私钥、cookie、完整文章 URL 或 URL 登录参数。
点击“停用本机 ADB”停止配对服务、取消当前任务并阻止后续工具连接，不修改手机系统的调试开关。
首次密钥授权未完成时会保留标记，阻止 Agent 自动重复触发授权弹窗；在设置页再次
点击连接按钮才会明确重试。取消或停用会关闭当前 socket。

## 构建与验证

按上游 README 准备原生库和 `engine.zip`，再执行：

```sh
ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleDebug -Pdsh.fetchShizuku=false
```

Debug 包名 `io.github.jackwener.dsh.custom`、名称 DSH Custom，可与已安装 DSH 共存，
保留其配置。新包需要自己的 ADB 授权与模型配置。Release 包名保持原样。
Debug 版可用 shell 的 DUMP 权限启动设置页：

```sh
adb shell am start -n io.github.jackwener.dsh.custom/io.github.jackwener.dsh.feature.settings.LocalAdbActivity
```

关键测试：`AdbKeyTest` 验证 ADB RSA 编码与原始挑战签名，`AdbServiceStreamTest`
验证双向通道流控和错误边界；`CapabilityListTest` 包含新工具的描述符预算检查。
完整模型调用仍需要在新包中配置模型服务。

`AdbClientTest` 通过本地 TCP 假 adbd 验证首次公钥授权与双向数据交换；
`node --test js/test/wechat-extraction.test.mjs` 执行 Kotlin 中实际嵌入的提取脚本，
覆盖可见阅读区、隐藏旧数值、正文干扰和字段缺失。

## 真机验收（2026-11-01）

- 开发 APK 构建、安装成功，原版 `io.github.jackwener.dsh` 保留。
- 新包 `io.github.jackwener.dsh.custom` 从设置页通过真实 CapabilityHub 依次执行三个工具。
- 本机 adbd 端口 5555 连接成功，发现微信文章 target；CDP 读取与私有快照保存成功。
- 修正为可见阅读区提取后重新安装、读取成功：公众号“子云之风”，文章
  《Deepseek-DSH的安卓版来了超越豆包》，页面显示阅读数 `75`。
- 本机成功报告位于 `build/validation/wechat-device-result.json`（构建产物，不进入 Git）。
- Gradle 定向测试：13 项通过，包括新协议/密钥测试和 CapabilityListTest。
- JavaScript：18 项模式测试、3 项提取回归测试通过。
- 验证经过能力桥的 Android 端执行；未配置新包模型服务，未运行真实模型对话调用。


## 无线配对真机验收（2026-11-01）

- Redmi K60 / Android 13，在 Custom 包内完成系统六位码配对，配对端口 43245。
- 实际连接端口 38269，返回 `connected: true, tls: true`；未使用 5555 或电脑转发。
- 配对成功记录与 TLS 连接报告：`build/validation/wireless-pairing-result.json`、
  `build/validation/wireless-status-result.json`。
- 定向 JVM / Robolectric 测试 17 项通过，包括配对包边界、RSA 证书身份与拒绝降级测试。
- Debug 构建有 DUMP 权限保护的验证 receiver，可在系统配对弹窗保持可见时后台验收；
  release 不包含此入口。配对码不写入验证报告。
- 微信读取成功：公众号“子云之风”，文章《Deepseek-DSH的安卓版来了超越豆包》，阅读数 `75`；
  结果 `tls: true, adb_port: 38269`，保存于 `build/validation/wireless-article-result.json`
  和应用私有目录 `no_backup/wechat/latest.json`。


## 通知配对与正式设置界面验收（2026-11-01）

- Redmi K60 / Android 13 / MIUI：从 DSH 页面启动、系统通知权限授权、系统开发者设置、
  mDNS 自动发现、通知 RemoteInput 输入六位码、SPAKE2 配对、TLS 自动连接均已走通。
- 产品执行全部在手机上：电脑仅用作安装及自动化点击，没有执行桌面 adb pair/connect/forward。
- 正式页面通过 CapabilityHub 读取文章成功，端口 38269，`tls: true`，阅读数 75；
  报告 `build/validation/notification-pairing-article.json`。
- 深浅色页面、系统引导弹窗和高级设置弹窗已真机检查；系统主题验收后恢复原值。
- 配对服务仅由用户操作启动，最多运行 150 秒，支持通知栏取消、页面停止、系统超时；
  完成或失败会停止发现与前台服务，保留结果通知。不会开机自启或持续后台扫描。
- 只接收当前会话的通知回复；错误长度不发起握手，过期回复忽略。NSD 只接受本机接口地址。
- 21 项定向 JVM / Robolectric 测试通过，含通知 PendingIntent 的显式目标、可变性、
  私密显示、完成后移除回复、过期回复与错误长度，以及既有 ADB / TLS / 提取入口测试。
- Android 11 以下显示版本说明；Android 14+ 使用 shortService 类型与超时回调。
  其他 ROM 尚未逐一真机验收。

通知栏“取消”已真机验证：前台服务立即退出；最终安装包再次从正式页面读取成功。

实现参考：[Android 直接回复通知](https://developer.android.com/develop/ui/views/notifications/build-notification#reply-action)、
[NSD API](https://developer.android.com/reference/android/net/nsd/NsdManager)、
[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#short-service)。

扫描任务期间复用一条 ADB/TLS 连接，完成或停止后释放，避免每个操作反复触发系统调试连接提示；
详见 [扫描期间保持连接](WECHAT-COLLECTION.md#扫描期间保持-adb-连接)。
