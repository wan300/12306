#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
12306 自动抢票系统 - 完整构建脚本

此脚本会：
1. 构建后端为可执行文件 (PyInstaller)
2. 构建前端 (Vite)
3. 使用Electron Builder打包成桌面应用

使用方法:
    python build_app.py [--skip-backend] [--skip-frontend] [--target windows|mac|linux]
"""

import os
import sys
import shutil
import subprocess
import argparse
from pathlib import Path

# 项目根目录
ROOT_DIR = Path(__file__).parent
FRONTEND_DIR = ROOT_DIR / "frontend"
BACKEND_DIR = ROOT_DIR / "backend"


def run_command(cmd, cwd=None, shell=True):
    """运行命令并实时输出"""
    print(f"\n>>> 执行: {cmd}")
    print("-" * 50)
    
    result = subprocess.run(
        cmd,
        cwd=cwd,
        shell=shell,
        text=True,
    )
    
    if result.returncode != 0:
        print(f"[ERROR] 命令执行失败: {cmd}")
        sys.exit(1)
    
    return result


def clean_build():
    """清理构建产物"""
    print("\n[STEP] 清理旧的构建产物...")
    
    dirs_to_clean = [
        BACKEND_DIR / "dist",
        BACKEND_DIR / "build",
        FRONTEND_DIR / "dist",
        FRONTEND_DIR / "release",
    ]
    
    for d in dirs_to_clean:
        if d.exists():
            print(f"   删除: {d}")
            shutil.rmtree(d)


def build_backend():
    """构建后端可执行文件"""
    print("\n" + "=" * 50)
    print("构建后端 (PyInstaller)")
    print("=" * 50)
    
    # 检查 PyInstaller
    try:
        import PyInstaller
    except ImportError:
        print("安装 PyInstaller...")
        run_command(f'"{sys.executable}" -m pip install pyinstaller')
    
    # 运行构建脚本（必须在 backend 目录执行，确保相对路径正确）
    run_command(f'"{sys.executable}" build_exe.py', cwd=BACKEND_DIR)
    
    # 验证构建结果
    backend_exe = BACKEND_DIR / "dist" / "12306-backend" / "12306-backend.exe"
    if not backend_exe.exists():
        print(f"[ERROR] 后端构建失败: {backend_exe} 不存在")
        sys.exit(1)
    
    print(f"[OK] 后端构建成功: {backend_exe}")


def build_frontend():
    """构建前端"""
    print("\n" + "=" * 50)
    print("构建前端 (Vite)")
    print("=" * 50)
    
    # 检查 node_modules
    if not (FRONTEND_DIR / "node_modules").exists():
        print("安装前端依赖...")
        run_command("npm install", cwd=FRONTEND_DIR)
    
    # 构建
    run_command("npm run build", cwd=FRONTEND_DIR)
    
    # 验证
    if not (FRONTEND_DIR / "dist" / "index.html").exists():
        print("[ERROR] 前端构建失败")
        sys.exit(1)
    
    print("[OK] 前端构建成功")


def build_electron(target="windows"):
    """使用Electron Builder打包"""
    print("\n" + "=" * 50)
    print("打包 Electron 应用")
    print("=" * 50)
    
    # 确保依赖已安装
    if not (FRONTEND_DIR / "node_modules" / "electron").exists():
        print("安装Electron依赖...")
        run_command("npm install", cwd=FRONTEND_DIR)
    
    # 构建命令
    if target == "windows":
        cmd = "npm run electron:build:win"
    elif target == "mac":
        cmd = "npm run electron:build:mac"
    elif target == "linux":
        cmd = "npm run electron:build:linux"
    else:
        cmd = "npm run electron:build"
    
    run_command(cmd, cwd=FRONTEND_DIR)
    
    # 检查输出
    release_dir = FRONTEND_DIR / "release"
    if release_dir.exists():
        print("\n[OK] 打包完成! 输出目录:")
        for f in release_dir.iterdir():
            print(f"   - {f.name}")
    else:
        print("[WARN] 打包可能未完成，请检查 frontend/release 目录")


def main():
    parser = argparse.ArgumentParser(description="构建 12306 抢票助手应用")
    parser.add_argument("--skip-backend", action="store_true", help="跳过后端构建")
    parser.add_argument("--skip-frontend", action="store_true", help="跳过前端构建")
    parser.add_argument("--clean", action="store_true", help="清理构建产物")
    parser.add_argument("--target", choices=["windows", "mac", "linux"], 
                       default="windows", help="目标平台")
    
    args = parser.parse_args()
    
    print("\n" + "=" * 50)
    print("12306 抢票助手 - 构建工具")
    print("=" * 50)
    
    if args.clean:
        clean_build()
        if not args.skip_backend and not args.skip_frontend:
            print("清理完成")
            return
    
    # 构建后端
    if not args.skip_backend:
        build_backend()
    else:
        print("\n[SKIP] 跳过后端构建")
    
    # 构建前端
    if not args.skip_frontend:
        build_frontend()
    else:
        print("\n[SKIP] 跳过前端构建")
    
    # 打包Electron
    build_electron(args.target)
    
    print("\n" + "=" * 50)
    print("构建完成!")
    print("=" * 50)
    print(f"\n安装包位置: {FRONTEND_DIR / 'release'}")
    print("\n提示: 运行安装包即可安装应用程序")


if __name__ == "__main__":
    main()
