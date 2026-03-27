import uvicorn
import os
import sys

# Ensure the current directory is in sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from main import app

if __name__ == "__main__":
    # Allow overriding host/port via environment to avoid port conflicts in packaged runs
    host = os.environ.get("BACKEND_HOST", "0.0.0.0")
    port = int(os.environ.get("BACKEND_PORT", "8000"))

    print("=" * 60)
    print("🚄 12306 后端服务启动中...")
    print("=" * 60)
    print(f"监听地址: http://127.0.0.1:{port}")
    print(f"API文档: http://127.0.0.1:{port}/docs")
    print("=" * 60)
    
    uvicorn.run(
        app, 
        host=host,  # 监听所有接口
        port=port, 
        log_config=None,
        access_log=True
    )
