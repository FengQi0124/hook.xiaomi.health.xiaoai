# 手环小爱 AI 增强（hook.xiaomi.health.xiaoai）

一个 **LSPosed / Xposed** 模块（libxposed 现代 API 102），注入**小米运动健康**（`com.mi.health`）进程内，拦截小爱同学的语音问答链路，把环上显示的回答替换为**任意大模型**的回复；支持多模型管理、手环语音切换模型、智能家居指令直通。

当前版本：**v0.6.0-beta1**（versionCode 600，包名 `com.zeroone01.xiaoai`）
仓库地址：<https://github.com/FengQi0124/hook.xiaomi.health.xiaoai>

> 0.5.0-beta 起，本工程基于 `mi-band-ai`（环上 LLM，`llm.miband.littlewhite`）整体重构，沿用其 Hook 链路与设置页骨架，新增多模型切换菜单、提供方预设、语音切换模型、智能家居指令直通。0.5.1-beta1 起新增自绘应用图标（运动手表 + AI 火花，自适应图标），并移除「我的」页入口注入、「启用模块」开关与预测性返回开关。0.6.0-beta1 起配置页按功能重新分栏（模型列表 / 其它 / 配置），并删除两条「模式持续时长」设置。

---

## 一、它是怎么工作的

```
手环上喊「小爱同学」
   └─► 小米运动健康 App 建立 AIVS WebSocket，云端下行 JSON 报文
         └─► 本模块在 Java 字符串层拦截（不碰密文、不碰证书）
               ├─ 上行 RecognizeResult(is_final)  → 捕获「你的问题」（按 dialog_id 暂存）
               └─ 下行 Template/Toast             → 拿暂存的问题去问大模型
                     └─► 改写 payload.text → chain.proceed(newArgs) 放行
                           └─► 手环上显示的是大模型的回答，TTS / 历史记录照常
```

- **三道 Hook 互为兜底**（任一命中即可工作）：

  | 层 | Hook 点 | 说明 |
  |---|---|---|
  | ① 主层 | `defpackage.oav#onMessage(WebSocket, String)` | 宿主自封装的 WebSocket 回调（最常见路径） |
  | ② 兜底 | `okhttp3.internal.ws.RealWebSocket#onReadMessage(String)` | okhttp 原生 WebSocket |
  | ③ 稳定 | `com.xiaomi.ai.api.common.APIUtils#readInstruction(String)` | AIVS 指令解析入口，类名未混淆 |

- **安全兜底**：任何异常、超时、非 2xx、空回复、主线程命中一律**放行原回答**，绝不干扰宿主（`ExceptionMode.PROTECTIVE`）；AI 等待上限 15s，日志自动脱敏（`sk-` 密钥、Authorization/Bearer 头）。
- **无代理、无证书、无后台服务**：替代了原 Termux + mitmproxy 方案（[`example/xiaoai.py`](example/xiaoai.py) 仅作留存参考），全部逻辑在宿主 App 内就地完成。

---

## 二、功能特性

### 回答来源

- **外部 LLM**（默认 DeepSeek）：OpenAI 兼容双路由（OpenAI / Anthropic），支持 DeepSeek 思考模式 `reasoning_content` 提取；
- ~~手机端小爱 · miclaw / fast~~ —— 0.5.0-beta 起「用手端小爱回答」开关已下线（`getUsePhoneXiaoai` 强制 false），仅保留代码链路。

### 多模型（0.5.0-beta 核心）

- 「模型列表」分组第 1 行固定**小爱同学**（不可编辑/删除），第 2 行起用户模型行尾有一个「**三横**」手柄：**点按**该行**行内展开精简编辑界面**（只露 **Base URL / 模型名 / API Key** 三栏 + **删除 · 取消 · 保存**，带展开-收起动画，整块只有三行输入框高、不弹任何浮层，再点一次收起），**长按 0.5 秒**进入排序态（横线变主题色 + 震动反馈），行变成圆角主题色卡片、**上下拖动**跟手移动，松手吸附落位并持久化（落位时各行直接停在新槽位，不会先飞离再弹回）；
- 列表末行是居中的「**+ 添加模型**」（0.6.0-beta1：整行只有「+ 添加模型」并水平居中，`+` 取 Miuix `MiuixIcons.Add`，点按打开添加对话框）；
- **行点击即激活**：小爱行 → `active_model=xiaoai` + `default_mode=xiaoai`；模型行 → `active_model=<id>` + `default_mode=llm`；
- 「添加/编辑」对话框内置常见提供方**预设 chips**（DeepSeek / 通义千问 / 智谱 / 硅基流动 / 火山方舟 / 讯飞星火 / Kimi / OpenAI / Gemini / OpenRouter），选中自动填 Base URL 与默认模型，只需填 API Key；
- API Key 落盘加密（`ApiKeyCipher` XOR + Base64），进程内明文；
- 紧随其后的「**其它**」分组里「**测试模型可用性**」：用当前激活模型请求一次，验证连接与 Key；
- 首次进入若无激活条目，自动用 legacy API 字段播种一条完成迁移。

### 语音与交互

- **手环语音切换模型**：说「换模型」→ 手环弹出编号菜单（30 秒有效）→ 说序号 / 中文数字 / 模型名切换；
- **智能家居指令直通**：命中 `SmartHomeRules` 词表（场景词 / 数值调节 / 动作+设备名词）时不调 LLM，直接交小爱原生执行；
- **打开设置页**：只走**桌面图标**（0.5.1-beta1 起「我的」页入口注入已整体移除，宿主内不再显示任何入口）。

### 设置页（Miuix / HyperOS 设计语言）

- **3-Tab**：`首页` / `配置` / `记录`（悬浮胶囊底部栏可选，或经典 NavigationBar）；
- 首页：工作状态卡（点击打开 LSPosed 管理器）→ 关于 → 快速操作 → 目标应用 → 日志导出；
- 配置：**模型列表**（小爱同学 → 居中的「+ 添加模型」）→ **其它**（测试模型可用性 / 智能家居指令直通）→ **配置**（切换模型提示词 / 系统提示词，0.6.0-beta1 由原「回答模式」栏改名并把系统提示词从「生成参数」移入；原「小爱模式持续时长」「LLM 模式持续时长」两条输入框已删除）→ 生成参数（超时 / 最大 Token）→ 会话设置；
- 记录：API 调用次数 / token 用量 / 柱状图 / 最近调用，可刷新与清除；
- 主题页：跟随系统 / 浅色 / 深色 + 毛玻璃、悬浮栏玻璃效果、页面缩放（0.5.1-beta1 起「预测性返回」开关已删除，返回手势走系统默认行为）。

---

## 三、安装与使用

### 3.1 环境要求

- Android 8.0+（minSdk 26，targetSdk 35）
- **LSPosed**（API 102，即 LSPosed ≥ 1.10）
- 作用域：`com.mi.health`（另声明 `com.xiaomi.wearable` / `com.xiaomi.hm.health` / `com.miui.voiceassist`）

### 3.2 安装步骤

1. 安装模块 APK（签名：`app/xiaoai.jks`）；
2. LSPosed 管理器启用模块，作用域勾选**小米运动健康**；
3. **强制停止**小米运动健康（必须，否则 Hook 不生效）；
4. 打开设置页：**桌面图标**（唯一入口）；
5. 「模型列表」分组里选/加一个模型，填 API Key → 点「其它」分组里的「测试模型可用性」验证。

### 3.3 手环语音切换模型

```
你：换模型
小爱：请选择模型
      1.小爱同学
      2.DeepSeek
      3.退出
你：2
小爱：已切换到 DeepSeek
```

30 秒不操作自动退出选择模式；设置页写入新激活模型时会清除 hook 进程的运行时覆盖。

---

## 四、项目结构

```
hook.xiaomi.health.xiaoai/
├── app/xiaoai.jks                    # 签名密钥（alias=xiaoai）
├── app/src/main/
│   ├── AndroidManifest.xml           # Xposed 声明 + SettingsActivity + StatsContentProvider
│   ├── resources/META-INF/xposed/    # 三件套：java_init.list / module.prop / scope.list
│   └── kotlin/com/zeroone01/xiaoai/
│       ├── MainModule.kt             # XposedModule 入口（按包名分发）
│       ├── SettingsActivity.kt       # 设置页 Compose Activity
│       ├── config/                   # ConfigKeys / ConfigStore / ModelEntry / PresetManager
│       │                             #   StatsContentProvider / StatsStore
│       ├── hook/
│       │   ├── LlmClient.kt          # 外部 LLM 客户端（OpenAI/Anthropic 双路由）
│       │   ├── MiHealthHook.kt       # com.mi.health WebSocket Hook
│       │   ├── WebSocketInterceptor.kt  # WsMessage + 回答来源回退链
│       │   ├── SmartHomeRules.kt     # 智能家居直通词表
│       │   ├── Bridge.kt             # 跨进程 localhost TCP 桥（43997）
│       │   ├── VoiceAssistHook.kt / XiaoaiAgentServer.kt / XiaoaiAgentClient.kt
│       │   └── FastXiaoaiEngine.kt   # fast 档（已下线入口，代码留存）
│       ├── log/LogCollector.kt       # 环形缓冲 + 文件导出，自动脱敏
│       └── ui/
│           ├── SettingsScreen.kt     # 主设置页（3-Tab + 模型列表 + 记录页）
│           ├── ThemeSettingsScreen.kt# 主题设置页（移植 KernelSU）
│           ├── Theme.kt / VisualPrefs.kt / BlurExt.kt
│           └── component/FloatingBottomBar.kt  # 悬浮胶囊底部栏（KSU 移植）
└── docs/                             # 可行性报告 / LSPosed 指南 / 逆向笔记
```

---

## 五、构建

### 本地构建

本工程使用本机 **Gradle 9.6.1**（Windows，`local.properties` 已配 SDK）：

```powershell
$env:JAVA_HOME='D:\Apps\build-tools\jdk-21'; $env:ANDROID_HOME='D:\Apps\Android\Sdk'
& 'D:\Apps\build-tools\gradle-9.6.1\bin\gradle.bat' :app:assembleRelease --no-daemon
```

### CI 构建（GitHub Actions）

`.github/workflows/build-apk.yml` 在**推送 main 分支**或**打 `v*` 标签**时自动构建：

1. JDK 17 + Android SDK platform 37.0 + Gradle 缓存；
2. `./gradlew assembleDebug assembleRelease`（`app/xiaoai.jks` 已入库，签名与本地一致）；
3. Debug / Release APK 上传为 workflow artifact；
4. **推 `v*` 标签时自动创建 GitHub Release** 并挂上两个 APK。

```bash
git tag v0.6.0-beta1
git push origin v0.6.0-beta1
```

- 产物：`app/build/outputs/apk/release/app-release.apk`（R8 混淆 + `app/xiaoai.jks` 签名）
- 校验：

```powershell
aapt2 dump badging app-release.apk          # 包名 / 版本 / launcher
apksigner verify --print-certs app-release.apk
```

### 技术栈

| 组件 | 版本 |
|---|---|
| Gradle / AGP / Kotlin | 9.6.1 / 9.3.1 / 2.4.10 |
| Xposed API | 102（libxposed 现代 API） |
| Compose Multiplatform | 1.12.0-rc01 |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | 0.9.4-rc01（UI，MIT） |
| androidx.activity-compose | 1.13.0 |
| kotlinx-serialization | 1.7.0 |
| HTTP | `java.net.HttpURLConnection`（刻意不引入 OkHttp，避免与宿主冲突） |
| compileSdk / minSdk / targetSdk | 37 / 26 / 35 |

---

## 六、调试

```bash
# 模块日志
adb logcat -s XiaoAiHijack:V
```

启动后应看到三道字符串层回调挂载完成；问一个复杂问题后应看到 `AI 替换：dialog=... q=...`。

| 现象 | 原因 | 解决 |
|---|---|---|
| 回复没被替换 | 超时 / 网络 / 主线程命中 | 看日志是「AI 替换」还是「放行原回答」，按对应行排查 |
| 找不到设置页入口 | —— | 0.5.1-beta1 起入口只有桌面图标（宿主内不再注入入口） |
| 某一层「未挂载」 | 宿主版本类名/签名变化 | 另两层仍兜底；抓新 APK 重新定位 |
| 播报的还是小爱 | 当前模型 = 小爱同学 | 切到第三方模型 |

---

## 七、版本历史

> 每次有**功能改动**都会同步升版本号（`versionName` + `versionCode` + `module.prop` 三处一起改）并打 `v*` 标签走 CI 出包，避免出现「版本号一样、功能不一样」的包。

- **0.6.0-beta1**（当前，versionCode 600）：配置页按功能重新分栏 —— 「模型列表」（小爱同学 → 居中的「**+ 添加模型**」，Miuix `MiuixIcons.Add` + 只留四个字 + 整行水平居中，原箭头行与长说明删除）、「其它」（测试模型可用性 / 智能家居指令直通）、「**配置**」（原「回答模式」栏改名，**切换模型提示词 + 系统提示词**合并到同一栏，系统提示词从「生成参数」移入）；**删除「小爱模式持续时长」「LLM 模式持续时长」两条输入框**（`ConfigStore` 取值与默认值保留，`ModeState` 仍按默认时长回退）；版本号 `0.5.1-beta1`(501) → **`0.6.0-beta1`(600)**。
- **0.5.1-beta1**：三横手柄交互重做 —— **点按**改为该行**行内展开精简编辑界面**（`ModelInlineEdit`：Base URL / 模型名 / API Key 三栏 + 删除·取消·保存，带展开-收起动画，只有三行输入框高，不弹浮层、也不必再多点一次「编辑」；上移/下移按钮删除，排序全靠长按拖动），**长按 500ms** 进入排序、拖动行以圆角主题色卡片样式**跟手**移动；修复松手落位后其余行「飞出去又回来」的跳变（重排时位移动画重新开始）；版本号由 `1.0.0`(1000) 改回 `0.5.1-beta1`(501)（该版本号期间存在多份功能不同的构建，故 0.6.0-beta1 起严格按功能升号）。
- **1.0.0**：自绘应用图标（`mipmap-anydpi-v26` 自适应图标 + `monochrome` 主题图标层，删除原项目 PNG）—— 运动手表造型（竖表带 + 白色圆盘 + 表盘内蓝色 AI 火花，青→蓝渐变底）；模型列表行尾改「三横」手柄，行高与内边距对齐 Miuix 偏好行（16dp 内边距、`headline1`/`body2` 字体，行块自带分隔线）；「智能家居指令直通」开关移入模型分组卡片（原「基本设置」分组删除）；删除「启用模块」开关（`isEnabled()` 恒 true）；删除「我的」页入口注入（`MinePageInjector` 移除，入口只走桌面图标）；删除预测性返回（Manifest `enableOnBackInvokedCallback`、反射兜底、开关、配置键与首页入口副标题全部移除）。
- **0.5.0-beta**：基于 mi-band-ai 整体重构 —— 多模型菜单 / 提供方预设 / 语音切模型 / 智能家居直通 / 「我的」页入口注入 / 3-Tab 设置页。
- **第二轮精简**：状态页改名「首页」并删三张信息卡；「关于」Tab 并入首页；删除手端小爱开关、三组旧指令词、思考模式等。
- **第三轮 UI 反馈修复**：预测性返回（Manifest `enableOnBackInvokedCallback` + 反射兜底）、悬浮栏跳转 bug、删莫奈取色与导航角标、删温度/Top P/Top K、「测试模型可用性」入配置 Tab、「统计」改名「记录」、导出日志移到首页底部、模型列表与状态卡排版重做。
- **0.2.x 及更早**：字符串层三道 Hook 架构、`xiaoai_hijack.py` mitmproxy 方案（已弃用，见 `example/`）。

---

## 八、免责声明

本项目仅供**个人学习与研究**使用，用于理解 Android 逆向工程、Xposed 框架与 AI 语音链路。请勿用于商业用途或违反相关服务条款的场景。使用本模块产生的一切后果由使用者自行承担。

## License

MIT
