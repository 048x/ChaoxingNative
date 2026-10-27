# ChaoxingNative（超星助手）

超星学习通视频任务自动学习工具的 **纯 Kotlin 原生 Android 版**，不需要 Python、Termux、Pydroid 或 Chaquopy。

> 仅供个人学习与技术研究使用，请遵守所在学校与平台的相关规定。

## 功能

- 账号密码登录（AES-CBC 加密提交，与原实现一致）
- 记住账号密码，下次打开自动回填，密码以掩码显示，点“登录”即可
- 读取课程列表（含二级文件夹）、章节列表
- 自动完成视频任务点，支持倍速、并发章节数设置
- 视频失败时自动尝试音频任务
- 未开放章节可选择重试或跳过
- 实时查看运行日志，支持主页返回、随时停止
- 学习任务运行在 WorkManager 前台服务中，退出界面后继续执行
- 支持 Android 8.0（API 26）及以上，横竖屏自适应

## 安装包

仓库根目录 `apk/ChaoxingNative-2.0-debug.apk` 可直接安装（debug 签名）。

正式发布建议自行生成签名后打包：

```bash
keytool -genkeypair -v -keystore cx-release.jks -alias cx \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码 \
  -dname "CN=Chaoxing, OU=Dev, O=Dev, L=SH, S=SH, C=CN"

./gradlew assembleRelease
apksigner sign --ks cx-release.jks --ks-key-alias cx \
  --out ChaoxingNative-2.0-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

## 编译

环境要求：Android Studio / Gradle 8.9+、JDK 17、Android SDK 34。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

工程已配置阿里云 Maven 镜像（`settings.gradle`），国内网络可直接同步依赖。

## 技术栈

- Kotlin + 协程（替代原 Python 多线程/多进程）
- OkHttp 4 + 持久化 CookieJar
- Jsoup 解析页面
- WorkManager 前台服务执行耗时任务
- ViewBinding + Material 3

## 目录结构

```
app/src/main/java/com/chaoxing/nativeapp/
├── App.kt              # Application 初始化
├── MainActivity.kt     # 三屏界面与交互
├── ChaoxingApi.kt      # 登录 / 课程 / 章节 / 进度上报
├── Parser.kt           # 页面与任务点解析
├── StudyEngine.kt      # 章节调度与重试（协程）
├── StudyWorker.kt      # WorkManager 前台任务
├── Net.kt              # OkHttp + Cookie 会话 + 限速
├── Aes.kt / Cfg.kt     # 加密与常量
├── Models.kt           # 数据模型
└── Logx.kt / StudyBus.kt / StudyRepo.kt
```

## License

[MIT](LICENSE)
