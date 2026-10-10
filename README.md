# 照片选片（Photo Cull）

探探式照片选片 Android APP：在平板/手机上本地滑动选片——**左滑=不喜欢、右滑=喜欢**，
全部划完一键把不喜欢的照片送入系统回收站（可随时撤销，可在图库「最近删除」恢复）。
用于拍照后快速筛片，替代在电脑上逐张翻看。

## 功能

- 相册主页：MediaStore 聚合所有相册（封面/名称/数量），点相册进入选片
- 全屏滑动选片：左滑不喜欢 / 右滑喜欢，松手有飞出动画，下一张无缝顶替
- 底部胶片预览条：全卷缩略图，点击跳转、可对已划照片重新滑动改判；
  红心=喜欢、灰心=不喜欢、白框=当前照片
- 撤销：左上角 ↩ 回退最近一次决定（连拍误划救星）
- 划完确认：先弹应用内摘要（喜欢 X / 不喜欢 Y），确认后走系统回收站授权
- 断点续选：决策实时落盘（state.json），中途退出可恢复；中途退出可「保留并结束」或「放弃更改」
- 自动送回收站：API 30+ 走 `MediaStore.createTrashRequest`（系统弹窗授权，可恢复）；
  API < 30 自动降级移入 `<相册>/.trash_选片` 文件夹

## 技术栈

| 项 | 说明 |
|---|------|
| 语言 | Java（`--release 8` 字节码），单 Activity 纯手写 UI |
| UI | 原生 `android.app` / `android.widget` / 自定义触摸手势，**无 androidx、无第三方库、零依赖** |
| 数据 | `MediaStore`（相册聚合/回收站）、EXIF 方向修正、JSON 状态持久化 |
| 系统 | minSdk 26（Android 8.0）/ targetSdk 30，横竖屏/平板自适应 |
| 构建 | **无 Gradle** 手工构建链：`aapt2 link → javac → d8 → zip 打包 → apksig 签名`，由 PowerShell 脚本驱动 |
| 权限 | `MANAGE_EXTERNAL_STORAGE`（本地相册直读，需用户在系统设置授予「所有文件访问」） |

## 仓库自带工具链（clone 即可构建，无需联网下载）

| 文件 | 用途 |
|---|------|
| `android.jar` | Android API 30 编译平台桩 |
| `aapt2.exe` | 资源/清单编译链接（Windows x64, 8.5.0-11315950） |
| `r8-8.3.37.jar` | D8 dex 编译器 |
| `apksig-8.5.0.jar` | APK v1+v2 签名 |
| `debug.keystore` | 调试签名密钥（密码 `123456`，仅调试用，正式发布请更换） |

## 环境要求

- Windows + PowerShell（系统自带 Windows PowerShell 或 pwsh 7 均可）
- **JDK 17**（Temurin 等均可），二选一：
  - 设置环境变量 `JAVA_HOME` 指向 JDK 根目录；或
  - 把 JDK 根目录路径写入本目录 `jdk_path.txt` 文件（一行，不入库）
- （可选，安装调试用）adb 在 PATH 中

## 构建与安装

```powershell
# 构建（产物: 照片选片.apk）
pwsh build.ps1

# 构建并安装到 USB 连接的设备
pwsh build.ps1 -Install
```

构建过程：aapt2 编译清单 → javac 编译 `app/java` → d8 转 dex → 打包进 APK → keystore 签名。

## 项目结构

```
├── app/
│   ├── AndroidManifest.xml            # 清单（权限、Activity 声明）
│   └── java/com/xcx/photocull/
│       └── MainActivity.java          # 全部逻辑（约 1100 行，按注释分块）
├── build.ps1                          # 一键构建脚本
├── android.jar / aapt2.exe / r8-*.jar / apksig-*.jar / debug.keystore
└── README.md
```

## MainActivity 代码结构速览

| 分块 | 内容 |
|---|------|
| UI 构建 | 相册主页、选片页（front/back 双卡片堆、红心标记、进度、撤销、胶片条） |
| 相册 | MediaStore 聚合相册与照片列表 |
| 状态 | `decisions`（LinkedHashMap<path,liked>，插入序=决策序）、state.json 断点续选 |
| 卡片显示 | 缩略图秒开 + 大图后台解码（代际守卫防竞态） |
| 滑动 | 自写手势判定（位移阈值+速度阈值），同步记账 + ghost 飞出动画 + advance 顶替 |
| 胶片条 | 只建一次，增量更新标记与白框；缩略图 LRU 缓存（512 张上限） |
| 回收站 | API30+ createTrashRequest / 低版本降级移动文件 |

## 签名说明

仓库提交的 `debug.keystore`（密码 `123456`）保证所有人构建出的 APK 签名一致、可互相覆盖安装。
**正式对外发布前**请生成自己的正式密钥并修改 `build.ps1` 中签名参数，切勿复用调试密钥。
