# Electron 桌面应用配置说明

本文档说明如何使用 Electron 将 12306 抢票助手打包成独立的桌面应用程序。

## 目录结构

```
frontend/
├── electron/
│   ├── main.js          # Electron 主进程
│   └── preload.js       # 预加载脚本
├── build-resources/     # 打包资源（图标等）
├── src/                 # Vue 前端源码
├── dist/                # Vite 构建输出
├── release/             # Electron 打包输出
├── package.json         # 项目配置
└── vite.config.js       # Vite 配置
```

## 开发模式

### 1. 分别启动前后端（推荐）

```bash
# 终端1：启动后端
cd backend
python run_server.py

# 终端2：启动前端开发服务器
cd frontend
npm run dev

# 终端3：启动 Electron
cd frontend
npm run electron:dev
```

### 2. 一键启动开发模式

```bash
cd frontend
npm run electron:dev
```

> 注意：使用一键启动需要确保后端服务已经在运行

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
- `12306抢票助手-1.0.0-x64.exe` - NSIS 安装程序（当前默认目标）

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

### Electron 主进程 (main.js)

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
