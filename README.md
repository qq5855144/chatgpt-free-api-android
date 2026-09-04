# ChatGPT Free API (Android)

> 把 **ChatGPT 网页版免费额度** 封装成 **OpenAI 兼容 API** 的 Android 应用。
> 灵感与架构参考 [deepseek-free-api](https://github.com/jonnyquan/deepseek-free-api)（DeepSeek 网页逆向转 API），本仓库将同一思路搬到 Android 端，并内置聊天界面。

**⚠️ 重要声明**
- 本项目仅用于**个人学习与自用**，逆向接口不稳定，**禁止对外提供服务或商用**。
- 使用非官方接口可能违反 ChatGPT 服务条款，存在账号限流/风控风险，风险自担。
- 令牌 = 账号凭证，仅保存在本机加密存储（Keystore），请勿泄露。

---

## 功能特性

| 需求 | 实现 |
|------|------|
| 支持登录 ChatGPT | 内置 WebView 登录页，登录后自动提取并保存令牌；也支持手动粘贴 |
| 保存 Cookie / Access Token / Session Token | Android Keystore 派生密钥 + EncryptedSharedPreferences 加密存储 |
| 逆向调用网页私有接口 | 直连 `chatgpt.com/backend-api/conversation`（SSE 流式协议逆向） |
| 网页免费额度 → API | 本地 NanoHTTPD 服务把网页会话翻译为 OpenAI 兼容接口 |
| App 内直接聊天 | 内置聊天 Tab，流式输出，多轮对话 |
| 免费用官方免费模型 | 默认 gpt-5 系列（gpt-5-6/gpt-5-5/mini 等），可自动拉取账号真实可用模型列表 |
| 令牌过期自动续期 | 401/403 时用 Session Token 调 `/api/auth/session` 换取新 AccessToken |
| 安装后实时自测 | 内置「调试」Tab：网络连通性 / 令牌校验 / 代理健康 / 模型列表 / 非流式 + 流式对话一键全链路自测 |
| 构建架构 | 仅保留 arm64-v8a（`abiFilters`），安装包面向 64 位设备 |

## 工作原理

```
┌──────────────┐   登录/粘贴令牌    ┌───────────────────────┐
│  ChatGPT 网页 │ ────────────────▶ │  本 App（手机）         │
│  免费账号会话  │                    │  ├ 令牌加密存储          │
└──────────────┘                    │  ├ 逆向客户端(OkHttp)   │
        ▲                           │  │  POST /backend-api/  │
        │ Bearer AccessToken        │  │  conversation (SSE)  │
        └───────────────────────────│  └──▲───────────────────┘
                                    │     │ OpenAI 兼容
                                    │  NanoHTTPD 本地服务       │
                                    │  GET /v1/models          │
                                    │  POST /v1/chat/completions│
                                    └─────┬─────────────────────┘
                                          │ http://127.0.0.1:8787/v1
                              ┌───────────┴────────────┐
                              │ 本机 App 内置聊天        │
                              │ 电脑/其他客户端(局域网)   │
                              └────────────────────────┘
```

逆向协议要点（对应 `net/ChatGPTClient.kt`）：

1. 以 `Authorization: Bearer <accessToken>` 调用 `POST https://chatgpt.com/backend-api/conversation`；
2. 请求体：`action=next` + `messages[]`（每条含随机 id / author / content.parts）+ `model` + `parent_message_id` 等（逆向自网页版请求）；
3. 响应为 `text/event-stream`，逐行解析 `data:` JSON，取 `message.content.parts`（每次为完整快照，用 `TextAccumulator` 差分出增量）；
4. 遇到 `message.end_turn == true` 表示回答结束；
5. 会话 id（`conversation_id`）可带回用于多轮续接。

## 构建

环境要求：Android Studio（Ladybug 或更新）、JDK 17。

```bash
git clone https://github.com/qq5855144/chatgpt-free-api-android.git
# 用 Android Studio 打开，等待 Gradle Sync 完成后 Run ▶
# 或命令行构建（仓库已内置 gradle wrapper，自动下载 Gradle 8.7）
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

> 架构：`app/build.gradle.kts` 中已通过 `ndk.abiFilters` 锁定 **arm64-v8a**（当前无原生 so 依赖，纯字节码 APK 实际仍兼容各架构；如需 32 位设备可移除该配置）。

### 签名（debug 与 release 签名一致，可覆盖安装）

- 仓库内置签名密钥：`keystore/cgfree-release.jks` + 根目录 `keystore.properties`（密码 `cgfree123`，alias `cgfree`）；
- **debug 与 release 统一使用该密钥签名**——本机构建、GitHub Actions 构建、历次 CI 产物签名完全一致，后续版本可直接覆盖安装，不会再出现「签名不一致」；
- 更换为自己的密钥（可选）：
  ```bash
  keytool -genkeypair -v -keystore my.keystore -alias cgfree -keyalg RSA -keysize 2048 -validity 36500 -storepass 你的密码 -keypass 你的密码 -dname "CN=ChatGPT Free API, OU=cgfree, O=cgfree, L=NA, ST=NA, C=CN"
  # 然后修改 keystore.properties；如需 CI 用新密钥，把 keystore base64 配到 Actions Secrets 的 KEYSTORE_BASE64（优先级高于 keystore.properties）
  base64 -w0 my.keystore   # Linux/macOS；Windows 用 certutil -encode
  ```
- ⚠️ 内置密钥公开于仓库，仅用于本项目自签 APK（学习自用）；介意者可自行更换（注意更换后旧包无法覆盖安装，需先卸载）。

## 使用步骤

### 1. 获取令牌（两种方式）

**方式 A：App 内网页登录（可能遇到 Cloudflare 人机验证）**
打开「账号」Tab → 网页登录 ChatGPT → 登录完成后点「我已登录，提取令牌」。

**方式 B：浏览器手动复制（推荐，最稳定）**
1. 电脑浏览器登录 [chatgpt.com](https://chatgpt.com)，随便发一条消息；
2. `F12` → `Application` → `Local Storage` → `https://chatgpt.com`，复制 `accessToken` 的 value（`eyJ…` 开头）；
3. `F12` → `Application` → `Cookies` → `https://chatgpt.com`，复制 `__Secure-next-auth.session-token` 的 value（`s%3A…` 开头，可选但建议保存，用于过期自动刷新）；
4. 在 App「账号」Tab 粘贴保存。

### 2. App 内直接聊天
切到「聊天」Tab，选择模型，输入消息即可（多轮对话自动携带历史）。

### 3. 开放 OpenAI 兼容 API（核心功能）
「API 服务」Tab：
1. 打开「开启 API 反向代理服务」（前台服务保活）；
2. 默认监听 `127.0.0.1:8787`（仅本机）；勾选「允许局域网访问」后同一 Wi-Fi 下的电脑/其他设备可访问；
3. **默认可复制访问密钥：`sk-cgfree-local`**（页面已预填，点「复制 Key」即可）——第三方客户端需携带 `x-api-key` 或 `Authorization: Bearer sk-cgfree-local`；清空密钥并重启服务则不校验（局域网建议保留密钥）；
4. 复制地址配置到任意 OpenAI 兼容客户端：点「复制完整配置」一次复制 Base URL + API Key + Model，或点「复制 curl 示例」直接得到可执行命令。

#### 接口
```http
GET  /v1/models
POST /v1/chat/completions
```

#### 请求示例（curl，密钥默认可直接用）
```bash
curl http://127.0.0.1:8787/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-cgfree-local" \
  -d '{
    "model": "gpt-5-5",
    "messages": [
      {"role": "system", "content": "你是一个助手"},
      {"role": "user", "content": "用一句话介绍 Android"}
    ],
    "stream": true
  }'
```

#### 在第三方客户端中使用（手机同一 Wi-Fi 的电脑）
| 客户端 | Base URL 配置 |
|--------|--------------|
| NextChat / ChatBox / Cherry Studio / LobeChat | `http://<手机IP>:8787/v1` |
| OpenAI SDK | `base_url = http://<手机IP>:8787/v1`，`api_key` 填 `sk-cgfree-local`（默认可复制密钥） |

> 手机 IP 在「API 服务」页会自动显示。多轮对话请由客户端携带完整 messages 历史（服务端按全量历史转发）。

### 4. 安装后实时自测（调试 Tab）
「调试」Tab 提供安装即用的全链路自测，无需电脑/抓包：

| 按钮 | 验证内容 |
|------|---------|
| ▶ 一键全链路自测 | 自动依次执行下方全部单项（代理未启动会自动拉起） |
| ① 网络连通性 | 探测 `chatgpt.com/cdn-cgi/trace`，区分「断网」与「风控拦截」 |
| ② 令牌·模型列表 | 用保存的 accessToken 调官方 `/backend-api/models`，验证令牌有效性与账号可用模型 |
| ③ 代理健康检查 | 本地代理 `GET /health` |
| ④ 代理模型列表 | 本地代理 `GET /v1/models` |
| ⑤ 对话·非流式 | 经本地代理真实发一条消息（stream=false） |
| ⑥ 对话·流式(SSE) | 经本地代理真实发一条消息（stream=true），统计 SSE 块数 |

状态卡实时显示登录态 / accessToken / sessionToken（脱敏）/ 代理运行状态，测试输出支持复制与清空。
典型排查路径：① 不通 → 检查网络/VPN；② 失败 → 重新登录换令牌；③④ 失败 → 看「API 服务」页日志；⑤⑥ 失败但 ③④ 通过 → 多为上游限流或模型不可用，换模型重试。

## 目录结构

```
app/src/main/java/com/cgfree/
├── MainActivity.kt            # 四 Tab 主界面（聊天/API服务/调试/账号）
├── data/
│   ├── Types.kt               # ChatMsg / ConversationRequest / 模型常量
│   ├── TokenStore.kt          # Keystore 加密令牌存储
│   └── Prefs.kt               # 端口/局域网/密钥/模型偏好
├── net/
│   └── ChatGPTClient.kt       # ★ backend-api 逆向客户端（SSE 解析、401 自动刷新）
├── proxy/
│   └── ProxyServer.kt         # ★ NanoHTTPD OpenAI 兼容反向代理
├── service/
│   └── ProxyService.kt        # 前台服务保活
├── ui/
│   ├── ChatFragment.kt        # 内置聊天（流式）
│   ├── ChatAdapter.kt
│   ├── AccountFragment.kt     # 令牌管理
│   ├── LoginActivity.kt       # WebView 登录 + 令牌提取
│   ├── ServerFragment.kt      # API 服务开关/地址/日志
│   └── DebugFragment.kt       # 调试自测（全链路一键验证）
└── util/
    ├── LogBuffer.kt           # 运行日志环形缓冲
    └── TextAccumulator.kt     # SSE 快照→增量差分器
```

## 常见问题

- **401/403 登录失效**：AccessToken 过期。保存了 SessionToken 会自动刷新；否则重新粘贴令牌。
- **429 请求频繁**：官方限流，等待片刻再试（免费模型限制请以官方为准）。
- **Cloudflare 验证**：WebView 登录可能被拦，改用电脑浏览器手动复制令牌（方式 B）。
- **代理启动失败**：端口被占用，换一个端口；检查日志（长按日志可清空）。
- **流式输出乱序/重复**：已内置快照差分算法，如仍有异常请提 Issue 并附模型名。

## 免责声明

本项目**仅供技术学习与研究**，请遵守 OpenAI/ChatGPT 服务条款与所在地法律法规。
因使用本项目产生的账号封禁、服务中断等后果由使用者自行承担。
仅限自用，禁止对外提供服务或商用，避免对官方造成服务压力。

## License

[MIT](LICENSE)
