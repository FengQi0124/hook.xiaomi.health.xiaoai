# XiaoAi Hijack — 小米运动健康 AI 助手接管模块

一个 **LSPosed / Xposed** 模块，用于在 **小米运动健康** App 内部接管小爱同学语音助手的问答链路，
把"小爱同学"的回答替换为任意 **OpenAI 兼容** 大模型的回答，并支持通过**手环语音**切换模型。

本模块是 [`xiaoai_hijack.py`](https://github.com/FengQi0124/xiaoai)（mitmproxy 中间人脚本）的**纯客户端实现**，
不需要 Root 证书、不需要代理、不需要抓包 —— 全部逻辑在 AIVS SDK 的 Java 层就地完成。

---

## 一、原理与关键 Hook 点分析

### 1.1 原脚本为什么能work

`xiaoai_hijack.py` 之所以能劫持成功，是因为它把 WebSocket 上的 JSON 包**截停、改写、再放行**。
链路上真正承载"用户说了什么"和"AI 回了什么"的只有两处：

| 方向 | namespace | name | 关键字段 |
|---|---|---|---|
| App → 云 | `SpeechRecognizer` | `RecognizeResult` | `is_final`、`results[0].origin_text` |
| 云 → App | `Template` | `Toast` | `text`、`query`、`disclaimer` |

脚本的核心时序等价于：

```
RecognizeResult(is_final=true)  ──► 记住 origin_text, dialog_id
                 ...（云端思考，可能要等几秒）...
Template.Toast                  ──► 用之前记住的 query 去问第三方 AI
                                    把 payload.text 换成 AI 的回复
                                    放行 → App 原样渲染，UI/播报/TTS 全部走原流程
```

**这里最关键的一点：脚本是在"转发前"修改 body，然后让原流程继续。**
App 侧完全不知道内容被换过 —— 播报、历史记录、卡片渲染全都正常。

### 1.2 迁移到 Xposed 的映射关系

AIVS SDK 在 Java 层是这样流转的（配合 `libaivs_jni.so`）：

```
native 收包
   └─► 在 Java 堆上构造 Message 对象（header 已填 namespace/name，payload 已反序列化）
         └─► dispatch(Message msg)          ← 多态分发方法，参数类型是 Message 或其子类
               └─► 各业务 Listener 消费 payload
```

所以 **只要在 dispatch 之前就地改写 payload 字段，效果和改 WebSocket body 完全等价**，
而且比改返回值更安全（返回值可能被 JNI 层直接忽略）。

挂载点一共 4 个，按重要性排序：

#### ① 消息分发方法（主战场）

```kotlin
// 不写死方法名/类名，用「参数类型是 Message 子类 + 参数个数 1~3」做指纹扫描
val dispatchCandidates = messageClass.subclasses
    .flatMap { it.declaredMethods }
    .filter { m -> m.parameterTypes.any { messageClass.isAssignableFrom(it) } }
```

命中的典型形态：
- `void onEvent(Event event)`
- `void onMessage(Message msg, Connection conn)`
- `void dispatch(Object o, int flag)`

**Hook 时机选 `beforeHookedMethod`** —— 我们要在业务层读到对象*之前*把字段改掉。

#### ② `Template.Toast.setText(String)` / `ToastStream.setMarkdownText(String)`

兜底方案。如果 ① 因为混淆或签名变化没命中，直接改 Setter 入参也能生效：

```kotlin
XposedBridge.hookAllMethods(toastClass, "setText") { param ->
    param.args[0] = rewrittenText
}
```

**注意**：必须**同时改字段和 Setter**，因为 JNI 层可能直接反射写字段，绕过 Setter。

#### ③ `ApiNameMapping.findClass(String namespace, String name)`

AIVS 用它把 `"Template.Toast"` 映射成具体 Class。Hook 它可以**无损拿到全部类映射表**，
是解决混淆最优雅的手段 —— 拿到映射后所有候选类直接从注册表里查，不用再猜类名。

#### ④ `Activity.onResume`（仅用于注入 UI 入口）

"我的"页面是原生 View（不是 Compose），所以用 `decorView` 树遍历找锚点文本，
在两个锚点之间 `addView` 插入入口。

### 1.3 三个必须解决的技术难点

#### 难点 1：类名全混淆，字段名不混淆 → 用字段指纹

`libaivs_jni.so` 要把 JSON key 映射到 Java 字段，所以 **JSON 里的 key 名 = Java 字段名**：

```java
class a.b.c {                      // 类名混淆成 a.b.c
    String origin_text;            // 字段名保留！
    boolean is_final;
    String markdown_text;
}
```

于是类发现策略变成：

```kotlin
// 一级：候选 FQN（含历史版本号变量）
val candidates = listOf(
    "com.xiaomi.ai.api.SpeechRecognizer\$RecognizeResult",
    "com.xiaomi.ai.api.Template\$Toast",
    ...
)
// 二级：字段指纹兜底 —— 找不到就全 DEX 扫描，找含这些字段名的类
val cls = Reflector.findClassByFields(listOf("origin_text", "is_final"))
```

`DexClassScanner` 是一个**手写的最小 DEX 解析器**（只读 `string_ids` 段，不引第三方库），
用来在运行时枚举 APK 里所有类名，配合字段过滤锁定目标。

#### 难点 2：Native 回调线程上做网络请求 → 用阻塞等待

这是整个项目最核心的设计。`Template.Toast` 的回调跑在 **native 回调线程 / HandlerThread**，
**不是主线程** —— 所以我们可以放心阻塞它，等价于 mitmproxy 的"暂停转发"：

```kotlin
val latch = CountDownLatch(1)
val holder = AtomicReference<String?>(null)

// 异步发起请求，不阻塞
scope.launch(Dispatchers.IO) {
    when (val r = AiClient.chat(...)) {
        is ChatResult.Success -> holder.set(r.text)
        is ChatResult.Failure -> XLog.w("失败，放行原回复：${r.reason}")
    }
    latch.countDown()
}

// 在 native 线程上等，最多 timeoutMs + 500，硬上限 10s
val ok = latch.await((timeoutMs + 500L).coerceAtMost(10_000L), TimeUnit.MILLISECONDS)
val reply = holder.get()
if (ok && !reply.isNullOrBlank()) rewrite(reply)   // 替换
else { /* 超时/失败 → 什么都不做，原回复照常下发 */ }
```

**三重保险防 ANR**：
1. `isMainThread()` 检查 —— 万一回调真在主线程，直接**跳过替换**，绝不冒 ANR 风险；
2. `callTimeout` 限制 OkHttp 单次请求上限；
3. `latch.await()` 硬超时，超时即放行原始回复，用户最多感觉"小爱回慢了一点"，不会卡死。

#### 难点 3：RecognizeResult 决定、Toast 才能写 → ThreadLocal 暂存

有个时序陷阱：
- `RecognizeResult` 阶段**必须**判断"这句话是不是语音指令"（比如"切换模型"），
  因为如果是指令，我们要**拦截它、返回菜单，且不能让云端真去回答**；
- 但那时**还没有 Toast 对象**，没法写回复。

解法是 ThreadLocal 暂存直通文本：

```kotlin
// RecognizeResult 阶段
engine.onRecognizeResult(msg)?.let { directText -> stashDirectText(directText) }
// 同一个线程稍后到 Toast 阶段
val pending = takeDirectText()   // 取到菜单文本
if (pending != null) rewrite(pending)   // 直接写入，不发网络请求
else { /* 走正常 AI 替换流程 */ }
```

### 1.4 安全退出策略

以下任意情况**立即放行原生回复**，模块对用户完全透明：

| 情况 | 行为 |
|---|---|
| `is_final == false`（中间结果） | 忽略，不记录 |
| 当前模型 = 小爱同学 | 完全不干预 |
| 当前模型 = 第三方但未填 API Key | 放行，并 Toast 提示去配置 |
| 网络失败 / 非 2xx | 放行 |
| 超时（默认 8s） | 放行 |
| 回调在主线程 | 放行 |
| 类解析失败 | 模块整体不接管 |

---

## 二、功能特性

- ✅ **模型选择**：小爱同学 / DeepSeek / 智谱 / 自定义（OpenAI 兼容）
- ✅ **预设厂商**：只需填 API Key，Base URL 已内置
- ✅ **自定义厂商**：Base URL + API Key + 模型名 全手填
- ✅ **流式支持**：解析 SSE，可选只取最终文本
- ✅ **手环语音切换模型**：说"切换模型" → 返回数字菜单 → 说 "1/2/3/4" 切换
- ✅ **多轮对话**：可开关，保留最近 N 轮上下文
- ✅ **超时兜底**：默认 8 秒，可调，超时自动放行
- ✅ **实时诊断页**：看日志、看最近对话、看替换是否成功
- ✅ **"我的"页面注入入口**：在"我的路线库"和"App设置"之间插入"AI 助手设置"
- ✅ **HyperOS 风格 UI**：基于 Miuix（Compose Multiplatform）

---

## 三、项目结构

```
xiaoai/
├── settings.gradle.kts              # 镜像仓库配置（阿里云 + 腾讯云）
├── build.gradle.kts                 # 根构建脚本
├── gradle.properties
├── gradle/
│   └── libs.versions.toml           # 版本目录
└── app/
    ├── build.gradle.kts             # 模块构建（含签名配置）
    ├── xiaoai.jks                   # 签名密钥
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── xposed_init
        │   └── META-INF/xposed/
        │       ├── module.prop
        │       ├── java_init.list
        │       └── scope.list
        ├── res/                     # 图标 / 主题 / 字符串
        └── java/com/fengqi/xiaoai/
            ├── XiaoAiApplication.kt
            ├── core/
            │   ├── XLog.kt              # 日志门面（环形缓冲 + logcat）
            │   ├── ModelId.kt           # 模型枚举 + 菜单序号
            │   ├── AiConfig.kt          # 配置数据类
            │   ├── ConfigStore.kt       # 存储接口
            │   ├── FileConfigStore.kt   # JSON 文件存储（跨进程）
            │   └── ModelManager.kt      # 全局单例（StateFlow）
            ├── net/
            │   └── AiClient.kt          # OpenAI 兼容客户端（SSE 流式）
            ├── hook/
            │   ├── Reflector.kt         # 反射工具（Optional 解包）
            │   ├── DexClassScanner.kt   # 手写 DEX 字符串扫描器
            │   ├── AivsModel.kt         # AIVS 类/字段解析
            │   ├── VoiceCommandHandler.kt # 语音指令状态机
            │   ├── InterceptEngine.kt   # 拦截核心（阻塞等待 + 替换）
            │   ├── MinePageInjector.kt  # "我的"页注入
            │   └── XiaoAiHookEntry.kt   # 主入口 + ApiRegistry
            └── ui/
                ├── SettingsActivity.kt  # 设置页容器
                ├── SettingsScreen.kt    # Miuix 设置界面
                ├── DiagnosticsScreen.kt # 诊断/日志页
                └── LauncherProxyActivity.kt # 透明启动代理
```

---

## 四、安装与使用

### 4.1 环境要求

- Android 7.0+（`minSdk 24`）
- 已安装 **LSPosed**（推荐）或 EdXposed
- 小米运动健康 App（`com.xiaomi.wearable` / `com.xiaomi.hm.health`）

### 4.2 安装步骤

1. 安装本模块 APK
2. 在 LSPosed 管理器中**启用模块**，作用域勾选 **小米运动健康**
3. **强制停止**小米运动健康（必须，否则 Hook 不生效）
4. 重新打开 App → 「我的」→ 找到「**AI 助手设置**」入口
5. 填入 API Key，选择模型，保存

### 4.3 手环语音切换

对着手环说：

```
你：切换模型
小爱：请选择模型
      1.小爱同学
      2.deepseek
      3.智谱
      4.退出
你：2
小爱：已切换到 DeepSeek
```

选定后，后续所有问答都由对应模型回答。手机上切换模型，手环立即同步（文件 mtime 轮询，1.5s）。

---

## 五、调试方法

### 5.1 看日志

```bash
# 全部模块日志
adb logcat -s XiaoAiHijack:V

# 只看错误
adb logcat -s XiaoAiHijack:E

# 抓 App 启动阶段的 Hook 安装过程
adb logcat -s XiaoAiHijack:V | grep -E "已挂载|解析|候选"
```

### 5.2 验证 Hook 是否生效

启动 App 后，日志里应该出现：

```
I/XiaoAiHijack: 开始安装 Hook，classLoader=...
I/XiaoAiHijack: 解析 AIVS 模型：Message=ok EventHeader=ok ... 
I/XiaoAiHijack: 已挂载分发方法: xxx.onEvent(Event)  [共 N 个]
I/XiaoAiHijack: ApiNameMapping 已挂载，已注册 M 个接口映射
I/XiaoAiHijack: MinePageInjector 已挂载
```

如果出现 `AIVS 模型解析失败`，说明类发现没命中 —— 此时看日志里打印的候选列表，
把新类名加进 `AivsModel` 的候选 FQN 列表即可。

### 5.3 实时诊断页

设置页底部 →「运行诊断」，可以看：
- 模块是否初始化、当前模型、配置同步状态
- **最近对话列表**：每条显示 `✓`（替换成功）/ `·`（放行原始回复）
- 原始 query ↔ 实际发出的回复

### 5.4 手动触发一次替换

在设置里把超时设为 20s，然后问一个需要思考的复杂问题：

```
你：用一句话解释什么是量子纠缠
```

如果 `✓` 出现且播报内容是第三方模型的风格（不带小爱的口癖），就是成功了。

### 5.5 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| 找不到设置入口 | Hook 未生效 | 强制停止 App 后重开；确认 LSPosed 作用域已勾选 |
| 日志显示"类解析失败" | App 版本变化 | 抓 `adb logcat` 里的候选类名，更新 `AivsModel` |
| 回复没被替换 | 回调在主线程 / 超时 | 看诊断页是 `·` 还是 `✓`；调大超时时间 |
| 播报的还是小爱 | 模型选的是小爱同学 | 切到第三方模型 |
| 手环说"切换模型"没反应 | 指令词不匹配 | 设置页可自定义指令词 |
| 配置改了手环端没同步 | 轮询间隔 | 等 1.5s；或重进设置页 |

---

## 六、构建

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

已配置签名（`app/xiaoai.jks`），密钥可用于 LSPosed 模块更新校验。

国内网络已配置阿里云 + 腾讯云 Maven 镜像（见 `settings.gradle.kts`）。

---

## 七、技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| AGP | 9.1.0 | 内置 Kotlin 支持 |
| Kotlin | 2.3.20 | |
| Compose Multiplatform | 1.12.0-rc01 | |
| Miuix | 0.9.4-rc01 | HyperOS 风格 UI |
| Xposed API | 82 | `compileOnly` |
| OkHttp | 4.12.0 | 网络 |
| kotlinx.serialization | 1.9.0 | JSON |
| kotlinx.coroutines | 1.10.2 | 异步 |
| compileSdk | 37.0 | 次版本 SDK |

---

## 八、免责声明

本项目仅供**个人学习与研究**使用，用于理解 Android 逆向工程、Xposed 框架与 AI 语音链路。
请勿用于商业用途或违反相关服务条款的场景。使用本模块产生的一切后果由使用者自行承担。

---

## License

MIT
