// native_hook.cpp — 原生 TLS 层 AIVS 拦截
// 住客端 libboringssl.so / libssl.so 的 SSL_read（纯零外部 hook 框架依赖的简易 arm64 内联跳板）。

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <android/log.h>
#include <sys/types.h>
#include <pthread.h>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <ctime>
#include <cstring>
#include <cstdio>
#include <cstdarg>
#include <string>
#include <algorithm>

#define LOG_TAG "XiaoAiN"
static int g_log_enable = 1;
#define LOGI(...)  do { if (g_log_enable) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGW(...)  do { if (g_log_enable) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// arm64 内联跳转跳板
// ---------------------------------------------------------------------------
struct HookContext {
    void *target_fn;
    void *hook_fn;
    uint8_t origin_bytes[16];
    void *trampoline;
};

static void *alloc_trampoline_page() {
    void *map = mmap(NULL, 8192, PROT_READ | PROT_WRITE,
                     MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
    if (map == MAP_FAILED) return NULL;
    return map;
}

static int set_page_prot(void *addr, size_t len, int prot) {
    uintptr_t page_size = (uintptr_t)sysconf(_SC_PAGESIZE);
    uintptr_t start = (uintptr_t)addr & ~(page_size - 1);
    uintptr_t end = (uintptr_t)addr + len;
    size_t size = (end + page_size - 1) & ~(page_size - 1);
    size -= start;
    return mprotect((void *)start, size, prot);
}

// 重定位单条 PC 相关 arm64 指令（B/BL/B.cond/CBZ/CBNZ/TBZ/TBNZ/ADR/ADRP/LDR-literal）
static uint32_t relocate_insn(uint32_t insn, uintptr_t src_pc, uintptr_t dst_pc) {
    int64_t diff = (int64_t)(dst_pc - src_pc);

    // B 或 BL: bits[31:26] = 000101
    if ((insn >> 26 & 0x3F) == 0x05) {
        int32_t old = (int32_t)(insn << 6) >> 6;  // sign-extend 26-bit imm
        int32_t ne = old + (int32_t)(diff / 4);
        return (insn & ~0x03FFFFFFu) | ((uint32_t)ne & 0x03FFFFFFu);
    }
    // CBZ/CBNZ imm19 bits[23:5]
    if ((insn >> 24 & 0xFFu) == 0x34) {
        int32_t old = (int32_t)(insn << 8) >> 13;  // sign-extend 19-bit
        int32_t ne = old + (int32_t)(diff / 4);
        return (insn & ~0x00FFFFE0u) | (((uint32_t)ne << 5) & 0x00FFFFE0u);
    }
    // TBZ/TBNZ imm14 bits[18:5]
    if ((insn >> 24 & 0xFFu) == 0x36) {
        int32_t old = (int32_t)(insn << 13) >> 18;  // sign-extend 14-bit
        int32_t ne = old + (int32_t)(diff / 4);
        return (insn & ~0x0007FFE0u) | (((uint32_t)ne << 5) & 0x0007FFE0u);
    }
    // B.cond bits[31:25]=0110101, imm19 bits[23:5]
    if ((insn >> 25 & 0x7Fu) == 0x35) {
        int32_t old = (int32_t)(insn << 8) >> 13;  // sign-extend 19-bit
        int32_t ne = old + (int32_t)(diff / 4);
        return (insn & ~0x00FFFFE0u) | (((uint32_t)ne << 5) & 0x00FFFFE0u);
    }
    // ADR/ADRP bits[31:24] = 0_xx_1_0000 (ADR immlo[1:0]=bits[30:29], ADRP immlo[1:0]=bits[30:29])
    if ((insn >> 24 & 0x9Fu) == 0x10u) {
        int32_t immlo = (insn >> 29) & 0x3;
        int32_t immhi = (int32_t)((insn << 8) >> 13);
        int64_t old_imm = ((int64_t)immhi << 2) | immlo;
        uint64_t orig_addr = src_pc + old_imm;
        int64_t new_imm = (int64_t)(orig_addr - dst_pc);
        int32_t ne_immlo = (int32_t)(new_imm & 0x3);
        int32_t ne_immhi = (int32_t)((new_imm >> 2) & 0x7FFFF);
        return (insn & ~(0x60000000u | 0x00FFFFE0u)) |
               ((uint32_t)ne_immlo << 29) | (((uint32_t)ne_immhi << 5) & 0x00FFFFE0u);
    }
    // LDR (literal) 64-bit bits[31:24]=01011000
    if ((insn >> 24 & 0xFFu) == 0x58u) {
        int32_t old = (int32_t)(insn << 8) >> 13;
        uint64_t orig_addr = src_pc + (int64_t)old * 4;
        int32_t ne = (int32_t)((int64_t)(orig_addr - dst_pc) / 4);
        return (insn & ~0x00FFFFE0u) | (((uint32_t)ne << 5) & 0x00FFFFE0u);
    }
    return insn;
}

static uint32_t arm64_literal_x17(int byte_off) {
    // LDR X17, #imm; imm19 = byte_off/4
    int32_t v = byte_off / 4;
    return 0x58000000u | (((uint32_t)(v & 0x7FFFF)) << 5) | 17u;
}
static uint32_t arm64_br_x17() { return 0xD61F0220u; }

static int install_hook(HookContext *ctx) {
    memcpy(ctx->origin_bytes, ctx->target_fn, 16);

    // 构建跳板：[被覆盖的原始 16 字节] + [强制跳回 target + 16]
    uint8_t tb[64];
    memset(tb, 0, sizeof(tb));
    uint32_t *dst = (uint32_t *)tb;
    uint8_t *src = (uint8_t *)ctx->target_fn;
    int n = 0;
    for (int i = 0; i < 4; i++) {
        dst[n] = relocate_insn(*(uint32_t *)(src + i * 4),
                               (uintptr_t)(src + i * 4),
                               (uintptr_t)(tb + n * 4));
        n++;
    }
    // 强制跳回 target + 16
    dst[n++] = arm64_literal_x17(8);   // LDR X17, #8
    dst[n++] = arm64_br_x17();          // BR X17
    uint64_t ret = (uintptr_t)ctx->target_fn + 16;
    memcpy(&dst[n], &ret, 8);
    n += 2;

    ctx->trampoline = alloc_trampoline_page();
    if (!ctx->trampoline) return -1;
    memcpy(ctx->trampoline, tb, n * 4);
    __builtin___clear_cache((char *)ctx->trampoline, (char *)ctx->trampoline + n * 4);

    // 改写 target 入口: LDR X17, #8 ; BR X17 ; <hook_fn>
    if (set_page_prot(ctx->target_fn, 16, PROT_READ | PROT_WRITE | PROT_EXEC) != 0)
        return -2;
    uint32_t *p = (uint32_t *)ctx->target_fn;
    p[0] = arm64_literal_x17(8);
    p[1] = arm64_br_x17();
    memcpy(&p[2], &ctx->hook_fn, 8);
    __builtin___clear_cache((char *)ctx->target_fn, (char *)ctx->target_fn + 16);
    set_page_prot(ctx->target_fn, 16, PROT_READ | PROT_EXEC);

    LOGI("hook 安装成功 target=%p hook=%p trampoline=%p", ctx->target_fn, ctx->hook_fn, ctx->trampoline);
    return 0;
}

// ---------------------------------------------------------------------------
// WebSocket 帧解析
// ---------------------------------------------------------------------------
struct WsFrame {
    const uint8_t *data;
    int len;
    int opcode;
    int fin;
};

static int parse_ws_frame(const uint8_t *buf, int buf_len, WsFrame *out) {
    if (buf_len < 2) return -1;
    int fin = (buf[0] >> 7) & 1;
    int opcode = buf[0] & 0x0F;
    int mask = (buf[1] >> 7) & 1;
    uint64_t plen = buf[1] & 0x7F;
    int pos = 2;
    if (plen == 126) {
        if (buf_len < 4) return -1;
        plen = ((uint64_t)buf[2] << 8) | buf[3];
        pos = 4;
    } else if (plen == 127) {
        if (buf_len < 10) return -1;
        memcpy(&plen, buf + 2, 8);
        pos = 10;
    }
    if (mask) {
        if (buf_len < pos + 4) return -1;
        pos += 4;
    }
    if (buf_len < pos + (int)plen) return -1;
    out->data = buf + pos;
    out->len = (int)plen;
    out->opcode = opcode;
    out->fin = fin;
    return 0;
}

// ---------------------------------------------------------------------------
// 手写 JSON 字段定位 —— 找 "key":"value" 里的 value 起止偏移（不含引号）
// ---------------------------------------------------------------------------
static int json_find_string_value(const uint8_t *json, int len, const char *key,
                                  int *out_start, int *out_end) {
    int klen = (int)strlen(key);
    if (klen == 0) return -1;
    for (int i = 0; i <= len - klen - 3; i++) {
        uint8_t c = json[i];
        // 在字符串内跳过
        // 简化：扫描到 "key":"value" 格式
        if (c != '"') continue;
        // 检查匹配 key (key 前一个字符必须是 , 或 { 或空格)
        if (i > 0) {
            uint8_t prev = json[i - 1];
            if (prev != '"' && prev != ',' && prev != '{' &&
                prev != ' ' && prev != '\t' && prev != '\n') continue;
        }
        // 比较 key
        if (i + 1 + klen >= len) continue;
        if (memcmp(json + i + 1, key, klen) != 0) continue;
        if (json[i + 1 + klen] != '"') continue;
        // 跳过 key 结束的 " 和空白 :
        int j = i + 1 + klen + 1;
        while (j < len && (json[j] == ' ' || json[j] == '\t' ||
                          json[j] == '\r' || json[j] == '\n' || json[j] == ':')) j++;
        if (j >= len || json[j] != '"') continue;
        int vs = j + 1;
        // 扫描 value 直到未转义的 "
        int ve = vs;
        int esc = 0;
        for (; ve < len; ve++) {
            if (esc) { esc = 0; continue; }
            if (json[ve] == '\\') esc = 1;
            else if (json[ve] == '"') break;
        }
        *out_start = vs;
        *out_end = ve;
        return 0;
    }
    return -1;
}

static int json_find_prefix(const uint8_t *json, int len, const char *needle) {
    int nlen = (int)strlen(needle);
    if (nlen > len) return -1;
    for (int i = 0; i <= len - nlen; i++) {
        if (memcmp(json + i, needle, nlen) == 0) return i;
    }
    return -1;
}

// ---------------------------------------------------------------------------
// 等长/短长 UTF-8 文本替换
// ---------------------------------------------------------------------------
static int replace_text_equal_len(uint8_t *buf, int vs, int ve,
                                  const char *replacement, int replace_bytes) {
    int orig_len = ve - vs;
    if (orig_len <= 0 || replace_bytes <= 0) return 0;

    // 按 UTF-8 字符边界截断到 orig_len - 1 内
    int char_end = 0;
    int bytes_used = 0;
    for (int i = 0; i < replace_bytes && bytes_used < orig_len; ) {
        uint8_t c = (uint8_t)replacement[i];
        int step = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : (c >= 0xC0) ? 2 : 1;
        if (bytes_used + step > orig_len) break;
        bytes_used += step;
        char_end = i + step;
        i += step;
    }
    // 复制 replacement
    memcpy(buf + vs, replacement, char_end);
    // 用全角空格 (3 bytes) 补齐到 orig_len
    int pos = vs + char_end;
    while (pos + 3 <= vs + orig_len) {
        buf[pos++] = 0xE3; buf[pos++] = 0x80; buf[pos++] = 0x80;
    }
    while (pos < vs + orig_len) buf[pos++] = ' ';
    return char_end;
}

// ---------------------------------------------------------------------------
// JNI 环境
// ---------------------------------------------------------------------------
static JavaVM *g_jvm = NULL;
static jobject g_bridge = NULL;
static jmethodID g_mid_on_asr = NULL;
static jmethodID g_mid_on_query_replace = NULL;

static JNIEnv *attach_thread() {
    if (!g_jvm) return NULL;
    JNIEnv *env = NULL;
    if (g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args = {JNI_VERSION_1_6, "XiaoAiN", NULL};
    if (g_jvm->AttachCurrentThread(&env, &args) != 0 || !env) return NULL;
    return env;
}

static void cb_notify_asr(const char *dialog_id, const char *text) {
    JNIEnv *env = attach_thread();
    if (!env) return;
    jstring jid = env->NewStringUTF(dialog_id);
    jstring jtxt = env->NewStringUTF(text);
    env->CallVoidMethod(g_bridge, g_mid_on_asr, jid, jtxt);
    env->DeleteLocalRef(jid);
    env->DeleteLocalRef(jtxt);
}

// 询问 Java 侧是否要替换下行消息
// @return 1 表示 out 被填入替换文本；0 表示不替换
static int cb_query_replace(const char *dialog_id, const char *ns,
                            const char *name, char *out, int cap) {
    JNIEnv *env = attach_thread();
    if (!env) return 0;
    jstring jid = env->NewStringUTF(dialog_id);
    jstring jns = env->NewStringUTF(ns);
    jstring jname = env->NewStringUTF(name);
    jstring jret = (jstring)env->CallObjectMethod(
        g_bridge, g_mid_on_query_replace, jid, jns, jname);
    env->DeleteLocalRef(jid);
    env->DeleteLocalRef(jns);
    env->DeleteLocalRef(jname);
    if (jret == NULL) return 0;
    const char *s = env->GetStringUTFChars(jret, NULL);
    int slen = (int)strlen(s);
    int n = std::min(cap - 1, slen);
    memcpy(out, s, n);
    out[n] = 0;
    env->ReleaseStringUTFChars(jret, s);
    env->DeleteLocalRef(jret);
    return 1;
}

// ---------------------------------------------------------------------------
// JSON 处理主流程
// ---------------------------------------------------------------------------
static int process_aivs_json(uint8_t *json, int len) {
    if (len < 40) return 0;  // 至少要有一个像样的 JSON

    int ns_s = 0, ns_e = 0, name_s = 0, name_e = 0;
    int did_s = 0, did_e = 0;
    json_find_string_value(json, len, "namespace", &ns_s, &ns_e);
    if (ns_e - ns_s <= 0 || ns_e - ns_s > 63) return 0;
    json_find_string_value(json, len, "name", &name_s, &name_e);
    if (name_e - name_s <= 0 || name_e - name_s > 63) return 0;
    json_find_string_value(json, len, "dialog_id", &did_s, &did_e);

    char ns[80] = {0}, name[80] = {0}, dialog_id[80] = {0};
    memcpy(ns, json + ns_s, (size_t)(ns_e - ns_s));
    memcpy(name, json + name_s, (size_t)(name_e - name_s));
    if (did_e > did_s && did_e - did_s < 80)
        memcpy(dialog_id, json + did_s, (size_t)(did_e - did_s));

    // ---------- 上行：SpeechRecognizer.RecognizeResult ----------
    if (strcmp(ns, "SpeechRecognizer") == 0 && strcmp(name, "RecognizeResult") == 0) {
        if (json_find_prefix(json, len, "\"is_final\":true") < 0 &&
            json_find_prefix(json, len, "\"is_final\": true") < 0) return 0;
        int ost = 0, oed = 0;
        if (json_find_string_value(json, len, "origin_text", &ost, &oed) != 0 ||
            oed - ost <= 0) return 0;
        char text[256] = {0};
        memcpy(text, json + ost, (size_t)std::min(oed - ost, 255));
        cb_notify_asr(dialog_id, text);
        return 0;  // 上行不做文本替换
    }

    // ---------- 下行：Template.* TOAST / Application.GenerateSpeak ----------
    int is_toast = (strncmp(ns, "Template", 8) == 0) &&
                   (strcmp(name, "Toast") == 0 ||
                    strcmp(name, "ToastV2") == 0 ||
                    strcmp(name, "ToastStream") == 0 ||
                    strcmp(name, "StyleToastStreamStart") == 0);
    int is_genspk = (strcmp(ns, "Application") == 0 &&
                     strcmp(name, "GenerateSpeak") == 0);
    if (!is_toast && !is_genspk) return 0;

    LOGI("AIVS-下行: %s.%s dialog=%s (%d bytes)", ns, name, dialog_id, len);

    // 询问 Java 侧是否要替换这个下行消息
    char repl[1024];
    if (cb_query_replace(dialog_id, ns, name, repl, sizeof(repl))) {
        int t_s = 0, t_e = 0;
        if (json_find_string_value(json, len, "markdown_text", &t_s, &t_e) == 0 &&
            t_e > t_s) {
            replace_text_equal_len(json, t_s, t_e, repl, (int)strlen(repl));
            return 1;
        }
        if (json_find_string_value(json, len, "text", &t_s, &t_e) == 0 &&
            t_e > t_s) {
            replace_text_equal_len(json, t_s, t_e, repl, (int)strlen(repl));
            return 1;
        }
    }
    return 0;
}

// ---------------------------------------------------------------------------
// SSL_read hook
// ---------------------------------------------------------------------------
typedef int (*ssl_read_fn)(void *ssl, void *buf, int num);
static ssl_read_fn g_real_ssl_read = NULL;

static int hook_ssl_read(void *ssl, void *buf, int num) {
    if (!g_real_ssl_read || num <= 0) return g_real_ssl_read(ssl, buf, num);
    int n = g_real_ssl_read(ssl, buf, num);
    if (n <= 0) return n;

    uint8_t *p = (uint8_t *)buf;

    if (p[0] == 0x81 || p[0] == 0x80 || p[0] == 0x82 ||
        p[0] == 0x01) {
        WsFrame fr;
        if (parse_ws_frame(p, n, &fr) == 0 && fr.opcode == 0x1) {
            process_aivs_json((uint8_t *)fr.data, fr.len);
        }
    } else if (p[0] == '{') {
        process_aivs_json(p, n);
    }
    return n;
}

// ---------------------------------------------------------------------------
// SSL_read 查找
// ---------------------------------------------------------------------------
static void *resolve_ssl_read() {
    const char *libs[] = {"libboringssl.so", "libssl.so"};
    for (int i = 0; i < 2; i++) {
        void *h = dlopen(libs[i], RTLD_NOLOAD | RTLD_LOCAL);
        if (!h) h = dlopen(libs[i], RTLD_GLOBAL | RTLD_LAZY);
        if (h) {
            void *fn = dlsym(h, "SSL_read");
            if (fn) { LOGI("找到 SSL_read @ %p in %s", fn, libs[i]); return fn; }
            dlclose(h);
        }
    }
    void *fn = dlsym(RTLD_DEFAULT, "SSL_read");
    if (fn) { LOGI("SSL_read via RTLD_DEFAULT @ %p", fn); return fn; }
    LOGE("找不到 SSL_read");
    return NULL;
}

// ---------------------------------------------------------------------------
// JNI 入口
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeroone01_xiaoai_core_NativeHook_nativeStart(JNIEnv *env, jobject thiz) {
    if (g_real_ssl_read) return JNI_TRUE;

    env->GetJavaVM(&g_jvm);

    jclass cls = env->GetObjectClass(thiz);
    g_bridge = env->NewGlobalRef(thiz);
    g_mid_on_asr = env->GetMethodID(cls, "onAsrRecognized",
                                     "(Ljava/lang/String;Ljava/lang/String;)V");
    g_mid_on_query_replace = env->GetMethodID(
        cls, "onQueryReplace",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)"
        "Ljava/lang/String;");
    if (!g_mid_on_asr || !g_mid_on_query_replace) {
        LOGE("未找到 NativeBridge 回调方法");
        return JNI_FALSE;
    }

    void *addr = resolve_ssl_read();
    if (!addr) return JNI_FALSE;
    g_real_ssl_read = (ssl_read_fn)addr;

    HookContext ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.target_fn = addr;
    ctx.hook_fn = (void *)hook_ssl_read;
    int rc = install_hook(&ctx);
    if (rc != 0) { LOGE("install_hook 失败 rc=%d", rc); return JNI_FALSE; }
    LOGI("SSL_read native hook 已启动");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeroone01_xiaoai_core_NativeHook_nativeStop(JNIEnv *env, jobject thiz) {
    g_real_ssl_read = NULL;
    if (g_bridge) { env->DeleteGlobalRef(g_bridge); g_bridge = NULL; }
    LOGI("SSL_read native hook 已停止");
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeroone01_xiaoai_core_NativeHook_nativeSetLogEnable(
    JNIEnv *env, jobject thiz, jboolean enable) {
    g_log_enable = enable;
}
