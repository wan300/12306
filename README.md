# 12306 自动化抢票系统

这是一个基于 Python FastAPI 和 Vue 3 开发的 12306 自动化抢票系统。包含后端 API 服务、前端管理界面以及一些独立的自动化脚本。

## 目录结构

- `backend/`: FastAPI 后端服务
    - `app/`: 应用代码
    - `data/`: 数据存储 (日志, Session 等)
- `frontend/`: Vue 3 + Vite 前端项目

## 环境准备

### 后端环境
- Python 3.10+

### 前端环境
- Node.js 16+
- npm 或 yarn

## 快速开始

### 1. 启动后端服务

进入 `backend` 目录，安装依赖并启动服务：

```bash
cd backend

# 安装依赖
pip install -r requirements.txt

# 启动服务 (默认端口 8000)
# 也可以使用: uvicorn main:app --reload
python main.py
```

Swagger API 文档地址: http://localhost:8000/docs

### 2. 启动前端界面

进入 `frontend` 目录，安装依赖并启动开发服务器：

```bash
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

访问地址: http://localhost:5173 (默认 Vite 端口)
- 本项目仅供学习交流使用，请勿用于非法用途。

## Docker 一键运行（前后端）

已提供前后端容器化配置，执行以下命令即可一键启动：

```bash
# 在项目根目录执行
docker compose up -d --build
```

启动后访问：

- 前端页面: http://localhost:5173
- 后端 API 文档: http://localhost:8000/docs

### 常用命令

```bash
# 查看服务状态
docker compose ps

# 查看日志
docker compose logs -f

# 仅看后端日志
docker compose logs -f backend

# 停止并移除容器
docker compose down
```

### 数据持久化

`docker-compose.yml` 已将宿主机目录 `backend/data` 挂载到容器内 `/app/backend/data`，数据库、会话、日志会被保留。

### 说明

- 该 Docker 方案为 Web 版前后端一键运行，不包含 Electron 桌面 GUI 容器化。
- 前端通过 Nginx 反向代理 `/api` 和 WebSocket 到后端容器，无需改动现有业务 API 调用。
