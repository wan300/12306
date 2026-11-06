# Electron 桌面应用配置说明

本文档说明如何使用 Electron 将 12306 抢票助手打包成独立的桌面应用程序。

## 目录结构

```
frontend/
├── electron/
│   ├── main.cjs         # Electron 主进程
│   ├── preload.cjs      # 预加载脚本
│   ├── flight-browser.cjs # 本机平台窗口与浏览器桥接
│   └── flight-page.cjs  # 个人机票页面适配
├── build-resources/     # 打包资源（图标等）
├── src/                 # Vue 前端源码
├── dist/                # Vite 构建输出
├── release/             # Electron 打包输出
├── package.json         # 项目配置
└── vite.config.js       # Vite 配置
```

## 开发模式

### 1. 启动桌面应用

```bash
cd frontend
npm ci
npm run electron:dev
```

Electron 会启动独立的 Python 后端，并自动选择本机端口。不要同时启动另一个后端来代替桌面后端；机票接口使用每次启动生成的本机令牌。

Python 解释器默认使用 `python`。如需指定虚拟环境，可在 PowerShell 中设置 `$env:PYTHON_PATH = '虚拟环境的绝对路径/python.exe'`。后端依赖按项目根目录说明安装。设置 `ELECTRON_DEBUG=1` 可自动打开开发者工具。

### 2. 直接打开个人机票

```bash
cd frontend
npm run electron:flights
```

已打包的程序可加 `--flights` 参数直接进入机票页。原有侧栏也提供“个人机票”，无需登录 12306。

## 个人机票

当前支持携程桌面页面和去哪儿 H5 的国内单程、经济舱、平台已保存的成人乘机人。通过正常个人预订页面完成查价和预订，不依赖企业分销账号，也不直接重放第三方私有接口。

1. 选择平台，点击“扫码登录”，使用对应平台 App 扫描应用内二维码并确认登录，并准备好常用乘机人及联系人。
2. 输入城市和日期，查询航班并选择目标。可按起飞时间早晚或票面价高低排序。列表价格是参考票面价，不含完整税费，也可能包含有资格限制的最低价。
3. 点击航班旁的“选择舱位”，在应用内查看产品、票面价、退改和行李规则，并选择舱位。
4. 填写平台保存的乘机人姓名和所有乘机人的人民币含税总预算，点击“核对预订信息”。应用内展示行程、旅客、联系人匹配情况、票面价和税费合计。平台页面在后台适配。
5. 保存任务后，在列表中单独点击“启动”。默认模式为核对预订信息。选择“自动提交一次订单，由我支付”后，任务才会在预算内自动尝试提交。

核对信息后可点击“提交订单”手动下单。提交前会再次匹配当前行程、旅客和含税总价。订单结果显示在核对区和任务列表中。

自动提交会在点击前再次核对行程、乘机人和总价。每个行程与乘机人组合只保留一次持久化提交机会，跨平台也防止重复提交。只有观察到平台成功响应和订单号，才显示订单待支付；支付和出票须到平台确认。提交超时、响应不明、提交过程中退出程序均不会自动重试。请先在平台核对这类订单，不要通过新建任务重复尝试。

默认检查间隔为五分钟，最短一分钟；同平台操作串行。关闭主窗口后托盘继续运行，退出程序或电脑休眠时停止查询。达到预算、订单待核对或需要处理登录时，会发出系统通知。

扫码确认后，查价、预订和监控使用同一平台会话。两家平台的会话加密保存在应用用户数据目录的 `flight-sessions` 中，启动时自动恢复。平台设置的到期时间保持原值，Cookie 更新或退出登录会同步保存。

### 本机控制接口

机票接口位于 `/api/v1/flights`。桌面应用通过 `window.electronAPI.flights.request(method, path, body)` 调用，传入的路径从 `/flights` 开始。HTTP 调用使用启动时生成的 `X-Flight-Token`。

| 方法 | 路径（相对于 `/api/v1/flights`） | 用途 |
| --- | --- | --- |
| POST | `/providers/{provider}/login/qr` | 获取二维码 PNG 数据地址和过期时间 |
| GET | `/providers/{provider}/login/status` | 查询扫码状态 |
| POST | `/search` | 查询航班 |
| POST | `/offers` | 查询指定航班的舱位产品 |
| POST | `/prepare` | 核对预订 |
| POST | `/orders` | 手动提交已核对的预订，含 `verified_total` 字段 |
| GET / POST | `/monitors` | 查询或创建任务 |
| POST | `/monitors/{id}/start` 或 `/monitors/{id}/pause` | 启停任务 |

平台标识为 `ctrip` 或 `qunar`。创建二维码时会替换该平台先前的扫码请求。返回的 `expires_at` 为 Unix 毫秒时间戳。

## 构建生产版本

### 方式一：使用构建脚本（推荐）

```bash
# 在项目根目录执行
python build_app.py

# 仅构建 Windows 版本
python build_app.py --target windows

# 跳过后端构建（如果后端已构建）
python build_app.py --skip-backend

# 清理构建产物
python build_app.py --clean
```

### 方式二：手动构建

```bash
# 1. 构建后端
python build_exe.py

# 2. 构建前端
cd frontend
npm run build

# 3. 打包 Electron
npm run electron:build:win   # Windows
npm run electron:build:mac   # macOS
npm run electron:build:linux # Linux
```

## 构建产物

打包完成后，在 `frontend/release/` 目录下会生成：

### Windows
- `12306抢票助手-1.1.0-x64.exe` - NSIS 安装程序（当前默认目标）

### macOS
- `12306抢票助手-1.0.0-arm64.dmg` - DMG 安装包
- `12306抢票助手-1.0.0-arm64.zip` - ZIP 压缩包

### Linux
- `12306抢票助手-1.0.0-amd64.AppImage` - AppImage
- `12306抢票助手-1.0.0-amd64.deb` - Debian 包

## 自定义图标

1. 准备一个 1024x1024 的 PNG 图标
2. 转换为各平台格式：
   - Windows: `.ico` 格式
   - macOS: `.icns` 格式
   - Linux: 多尺寸 PNG

3. 将图标放入 `frontend/build-resources/` 目录

## 配置说明

### package.json 中的 build 配置

```json
{
  "build": {
    "appId": "com.12306.ticket-helper",
    "productName": "12306抢票助手",
    "extraResources": [
      {
        "from": "../backend/dist/12306-backend",
        "to": "backend"
      }
    ]
  }
}
```

- `extraResources`: 将后端可执行文件打包到应用资源中
- `nsis`: Windows 安装程序配置（支持自定义安装目录）
- `mac`/`linux`: 其他平台配置

### Electron 主进程 (main.cjs)

- 自动启动后端服务
- 加载前端页面
- 处理窗口生命周期
- IPC 通信

## 常见问题

### Q: 启动时提示"Backend executable not found"

A: 确保先运行 `python build_exe.py` 构建后端，或检查后端可执行文件路径。

### Q: Electron 打开后页面能显示，但接口请求失败

A: 检查后端是否已正常启动并监听本机端口；在生产模式下应用会自动探测后端健康状态。

### Q: 打包后 API 请求失败

A: 检查 `src/api/index.js` 中的 baseURL 配置，确保生产模式使用正确的地址。

### Q: 图标未显示

A: 确保 `build-resources/` 目录下有正确格式的图标文件。

## 技术栈

- **前端**: Vue 3 + Vite + Element Plus
- **后端**: FastAPI + Uvicorn + PyInstaller
- **桌面**: Electron + electron-builder
