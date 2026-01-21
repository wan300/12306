# 12306抢票助手 - 构建资源目录

此目录包含 Electron Builder 打包时使用的资源文件。

## 需要的文件

### Windows
- `icon.ico` - Windows 应用图标 (256x256 像素，包含多尺寸)

### macOS
- `icon.icns` - macOS 应用图标

### Linux
需要创建 `icons` 目录，包含多个尺寸的 PNG：
- `icons/16x16.png`
- `icons/32x32.png`
- `icons/48x48.png`
- `icons/64x64.png`
- `icons/128x128.png`
- `icons/256x256.png`
- `icons/512x512.png`

## 图标制作建议

1. 准备一个 1024x1024 的高清图标原图
2. 使用在线工具转换：
   - https://icoconvert.com/ (生成 .ico)
   - https://cloudconvert.com/png-to-icns (生成 .icns)
3. 使用脚本批量生成不同尺寸

## 临时解决方案

如果暂时没有图标，可以：
1. 注释掉 package.json 中的 icon 配置
2. 或使用 Electron 默认图标
