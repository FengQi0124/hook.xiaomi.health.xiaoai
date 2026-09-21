# -*- coding: utf-8 -*-
"""
xiaoai_hijack.py (v6 - 支持手环语音切换模型)

在 v5 基础上新增：
  - 实时监听 ASR 识别结果，检测"切换模型"指令
  - 命中时直接把手环回复替换为模型选择菜单（不经过 AI）
  - 选择模式下收到数字 → 切换模型 + 回复确认
  - 支持多模型：deepseek / zhipu / openai / moonshot

用法：
    export DEEPSEEK_API_KEY="your-key"
    export DEEPSEEK_API_URL="https://api.deepseek.com/v1"
    export DEEPSEEK_MODEL="deepseek-chat"
    export DEFAULT_PROVIDER="deepseek"
    mitmdump -s xiaoai_hijack.py
"""

import asyncio
import json
import os
import time

import httpx
from mitmproxy import http, ctx

TARGET_HOST = "speech.ai.xiaomi.com"

DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
DEEPSEEK_API_URL = os.environ.get("DEEPSEEK_API_URL", "https://api.deepseek.com/v1")
DEEPSEEK_MODEL   = os.environ.get("DEEPSEEK_MODEL", "deepseek-chat")

AI_PROVIDERS = {
    "deepseek": {
        "name": "DeepSeek",
        "url":  os.environ.get("DEEPSEEK_API_URL", "https://api.deepseek.com/v1"),
        "key":  os.environ.get("DEEPSEEK_API_KEY", ""),
        "model": os.environ.get("DEEPSEEK_MODEL", "deepseek-chat"),
    },
    "zhipu": {
        "name": "智谱 GLM",
        "url":  os.environ.get("ZHIPU_API_URL", "https://open.bigmodel.cn/api/paas/v4"),
        "key":  os.environ.get("ZHIPU_API_KEY", ""),
        "model": os.environ.get("ZHIPU_MODEL", "glm-4-flash"),
    },
    "openai": {
        "name": "OpenAI",
        "url":  os.environ.get("OPENAI_API_URL", "https://api.openai.com/v1"),
        "key":  os.environ.get("OPENAI_API_KEY", ""),
        "model": os.environ.get("OPENAI_MODEL", "gpt-4o-mini"),
    },
    "moonshot": {
        "name": "月之暗面",
        "url":  os.environ.get("MOONSHOT_API_URL", "https://api.moonshot.cn/v1"),
        "key":  os.environ.get("MOONSHOT_API_KEY", ""),
        "model": os.environ.get("MOONSHOT_MODEL", "moonshot-v1-8k"),
    },
}

CURRENT_PROVIDER = os.environ.get("DEFAULT_PROVIDER", "deepseek")

SYSTEM_PROMPT = (
    "你是一个语音助手，通过小米手环回答用户问题。"
    "用户使用语音输入，可能有少许错别字，请合理理解。"
    "回答要简洁，尽量控制在80字以内，不要使用markdown格式，"
    "不要输出表情符号。"
)

MAX_WAIT_SECONDS = 8.0
SWITCH_TIMEOUT = 30

pending_queries: dict[str, str] = {}
switching_mode: bool = False
switching_dialog_id: str = ""
switching_enter_time: float = 0


def get_current_provider() -> dict:
    return AI_PROVIDERS.get(CURRENT_PROVIDER, AI_PROVIDERS["deepseek"])


async def call_ai(query_text: str) -> str:
    prov = get_current_provider()
    if not prov["key"]:
        return f"当前模型 {prov['name']} 未配置 API Key"

    headers = {
        "Authorization": f"Bearer {prov['key']}",
        "Content-Type": "application/json",
    }
    body = {
        "model": prov["model"],
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": query_text},
        ],
        "max_tokens": 200,
        "temperature": 0.7,
    }
    url = prov["url"].rstrip("/") + "/chat/completions"

    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post(url, headers=headers, json=body)
        resp.raise_for_status()
        data = resp.json()
        return data["choices"][0]["message"]["content"].strip()


def build_menu_text() -> str:
    lines = ["请选择模型"]
    keys = list(AI_PROVIDERS.keys())
    for i, key in enumerate(keys, 1):
        p = AI_PROVIDERS[key]
        marker = " ✓" if key == CURRENT_PROVIDER else ""
        lines.append(f"{i}.{p['name']}{marker}")
    lines.append(f"{len(keys) + 1}.退出")
    return "\n".join(lines)


def parse_choice(text: str):
    t = text.strip().replace("。", "").replace(",", "").replace(".", "")
    t = t.replace("号", "").replace("第", "").replace("个", "").replace("选项", "")
    try:
        return int(t)
    except ValueError:
        pass
    cn = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}
    for k, v in cn.items():
        if k in t:
            return v
    return None


def is_switch_command(text: str) -> bool:
    cmds = ["切换模型", "换一个模型", "切换ai", "更换模型", "换模式", "切换模式", "切换ai模型"]
    t = text.strip().lower().replace(" ", "").replace("\u3000", "")
    return any(c in t for c in cmds)


async def websocket_message(flow: http.HTTPFlow):
    if TARGET_HOST not in flow.request.pretty_host:
        return

    assert flow.websocket is not None
    message = flow.websocket.messages[-1]

    if message.from_client:
        return
    if not message.is_text:
        return

    global switching_mode, switching_dialog_id, switching_enter_time, CURRENT_PROVIDER

    try:
        data = json.loads(message.text)
    except Exception:
        return

    header = data.get("header", {})
    namespace = header.get("namespace")
    name = header.get("name")
    dialog_id = header.get("dialog_id", "")

    # ── 1. 记录 ASR 识别结果 ──────────────────────────────────
    if namespace == "SpeechRecognizer" and name == "RecognizeResult":
        payload = data.get("payload", {})
        if payload.get("is_final") is True:
            results = payload.get("results", [])
            query_text = results[0].get("origin_text", "") if results else ""
            if query_text:
                pending_queries[dialog_id] = query_text
                ctx.log.info(f"[xiaoai] 识别文本 dialog={dialog_id}: {query_text!r}")
                if is_switch_command(query_text):
                    switching_mode = True
                    switching_dialog_id = dialog_id
                    switching_enter_time = time.time()
                    ctx.log.info("[xiaoai] ★ 检测到切换模型指令，进入选择模式")
        return

    # ── 2. 命中 Toast（回复消息） ────────────────────────────
    if namespace == "Template" and name == "Toast":
        query_text = pending_queries.pop(dialog_id, "")

        # ☆ 选择模式处理
        if switching_mode:
            elapsed = time.time() - switching_enter_time
            if elapsed > SWITCH_TIMEOUT:
                switching_mode = False
                ctx.log.info("[xiaoai] 选择模式超时，自动退出")

            if not query_text:
                return

            choice = parse_choice(query_text)
            prov = get_current_provider()
            keys = list(AI_PROVIDERS.keys())
            exit_choice = len(keys) + 1

            if choice is None:
                menu = build_menu_text()
                reply = f"没有听懂，请说数字选择：\n{menu}"
            elif choice == exit_choice:
                switching_mode = False
                reply = f"已退出模型选择。\n当前模型：{prov['name']}"
            elif 1 <= choice <= len(keys):
                picked_key = keys[choice - 1]
                picked = AI_PROVIDERS[picked_key]
                if not picked["key"]:
                    menu = build_menu_text()
                    reply = f"{picked['name']} 还没有配置 API Key。\n{menu}"
                else:
                    CURRENT_PROVIDER = picked_key
                    switching_mode = False
                    reply = f"已切换到 {picked['name']}"
            else:
                menu = build_menu_text()
                reply = f"无效选项，请说 1 到 {exit_choice}：\n{menu}"

            data["payload"]["text"] = reply
            message.text = json.dumps(data, ensure_ascii=False)
            ctx.log.info(f"[xiaoai] 选择模式应答: {reply!r}")
            return

        # ☆ 首次发现切换指令（Toast 来自云端正常回复）
        if query_text and is_switch_command(query_text):
            menu = build_menu_text()
            data["payload"]["text"] = menu
            message.text = json.dumps(data, ensure_ascii=False)
            ctx.log.info("[xiaoai] 进入选择模式，回复菜单")
            return

        # ── 常规 AI 替换 ───────────────────────────────────────
        if not query_text:
            ctx.log.info(f"[xiaoai] dialog={dialog_id} 无对应识别文本，放行原始回答")
            return

        prov = get_current_provider()
        ctx.log.info(f"[xiaoai] dialog={dialog_id} 模型={prov['name']} 提问={query_text!r}")
        try:
            reply_text = await asyncio.wait_for(
                call_ai(query_text), timeout=MAX_WAIT_SECONDS
            )
        except asyncio.TimeoutError:
            ctx.log.info(f"[xiaoai] dialog={dialog_id} AI超时，放行原始回答")
            return
        except Exception as e:
            ctx.log.error(f"[xiaoai] dialog={dialog_id} AI出错: {e}，放行原始回答")
            return

        data["payload"]["text"] = reply_text
        message.text = json.dumps(data, ensure_ascii=False)
        ctx.log.info(f"[xiaoai] dialog={dialog_id} 已替换: {reply_text!r}")
