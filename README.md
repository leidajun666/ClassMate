# ClassMate 课表

> 一个 Material 3 风格、数据完全留在本机的 Android 课程表。
> 三门导入方式（AI 识图 / 教务文件 / 教务网页登录），不用手打一学期。

[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](#)
[![Material 3](https://img.shields.io/badge/Material-3-757575?logo=materialdesign&logoColor=white)](#)
[![APK](https://img.shields.io/badge/APK-4.4%20MB-1E88E5)](#)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

课程数据全部存在手机本地 SQLite：不需要注册、不需要账号、不上传服务器。
除了「网页登录抓取」这一步会打开你指定的教务网址，其余功能全部离线可用。

## 下载

到 [Releases](../../releases/latest) 下载最新的 `课表-ClassMate-x.y.z.apk` 直接安装（Android 8.0 及以上）。
覆盖安装会保留已有课程；包名 `com.classmate.timetable`，调试包带 `.debug` 后缀，可与正式包共存。

## 为什么会有这个 App

教务系统导出的课表长得五花八门：有的是 `.ics`，有的是「导出 Excel」但实际是 HTML 表格的 `.xls`，
有的干脆只有一张截图。学校自己的 App 往往只能看本周、还塞满广告。
所以这里做了三条互不依赖的导入路径，任选一条能走通就行：

| 导入方式 | 适合什么情况 | 是否联网 |
| --- | --- | --- |
| **教务文件导入** | 教务系统能导出 ics / csv / xls / xlsx / json | 完全离线 |
| **AI 识图导入** | 手里只有课表截图，或只能截屏 | 你自己发给 AI（App 不联网） |
| **教务网页登录抓取** | 教务系统什么都不给，只能在线看 | 需要打开教务网址 |

三种方式最终都落到同一个「预览 → 逐条勾选 → 点铅笔修正 → 批量入库」的流程，
不存在“识别错了只能重来”的死路。

## 功能

### 课表主界面
- 周课表网格：左侧节次/时间列 + 周一~周日（可隐藏周末，5 列显示更宽松）
- 表头显示每周实际日期，**今天所在列高亮**，非本周课程自动淡化
- 一门课跨多节自动占多行；同格子多门课自动左右分栏，不会互相遮挡
- 顶部「下一节」卡片：下一节课是什么、在哪、还有多久
- 左右箭头切换周次，一键「回到本周」；记住上次浏览到第几周
- 点击课程弹出详情（教师/地点/备注/编辑/删除），**长按空白格子**快速在该时段新建课程

### 课程管理
- 名称、教师、地点、备注
- 星期（7 天）、节次起止（默认 12 节，可在设置里扩到 16 节并自定义每节时间）
- 周次起止 + 单/双周
- 12 种课程颜色，自动按课程名分配默认色
- 保存时检测**时间冲突**并提示
- 深色模式 / Material You 动态取色（Android 12+）

### AI 识图导入
1. 打开「AI 识图导入」，点 **复制提示词**；
2. 把提示词 + 课表截图一起发给任意能看图的 AI（豆包 / 元宝 / 通义 / ChatGPT / Claude 都行）；
3. 回到 App 点 **从剪贴板粘贴**（或选 AI 存下来的 `.json` / `.txt` 文件），自动解析成课程列表，勾选后批量导入。

解析器对 AI 输出做足兼容：
- 能从「好的，以下是识别结果：\`\`\`json …\`\`\`」这类夹杂解释的回复里抠出 JSON；
- 支持中英文字段名（`name` / `课程名` / `courseName`，`day` / `星期`，`periods` / `节次`…）；
- 星期支持 `3`、`周三`、`星期三`、`Wednesday`、`wed`；
- 节次支持 `startPeriod/endPeriod` 两个字段、`"3-4节"` 字符串、`[3,4]` 数组；
- 周次支持 `1-16`、`第1-16周`、`1-16周（单）`（自动识别单双周）；
- 字段缺失给默认值，越界值自动裁剪到当前节次数量 / 总周数内；
- 完全不是 JSON 时，退一步按「星期 + 节次 + 周次」特征逐行解析纯文本，仍能拿到草稿；
- 也支持从其他 App 的分享菜单直接「分享到课表」。

每一条解析结果右侧有铅笔按钮，可改课程名、星期、起止节次，改完再导入。

### 教务导入（文件 / 网页）

**方式一：选文件**
- 支持教务系统导出的 `.ics`（日历）、`.csv/.tsv/.txt`、`.xlsx`，以及「导出 Excel」得到的 `.xls`（其实是 HTML 表格）、`.json`
- 全本地解析：自动嗅探格式与编码（UTF-8 / GBK / GB18030 都能读），自己解 zip 读 xlsx，**不依赖任何第三方解析库**
- 解析规则与教务导出一致：表头认星期（周一 / 星期一 / Mon…）、左列认节次（`1-2节` / `第3节`）、格子里再分课程名 / 教师 / 地点 / 周次
- 周次支持 `1-16周`、`1-16周(单)`、`1-8,10-16周`：单双周直接落到「单/双周」字段，跳周自动拆成两条课程
- rowspan 把一门连堂课拆成「第 1 节」「第 2 节」两格的，收尾时自动合并回 `1-2节`
- 老式二进制 `.xls`（OLE2）会明确提示「另存为 xlsx 或 csv」

**方式二：网页登录抓取（兜底）**
- App 内置浏览器，直接登录教务系统，进入课表页后点「抓取本页课表」
- 抓的是页面上所有 `<table>`（含 rowspan/colspan），不依赖某个学校的具体接口，再交给和文件导入完全相同的网格解析器
- 默认 **手机 UA**；页面排版错乱时可在菜单切「桌面版网页」
- 菜单「教务入口」内置若干常用地址（含北京校区 / 保定校区），点一下切换，网址会记住
- 抓不到时提供「复制页面文字」：把文字交给 AI 输出 JSON，再用 AI 识图导入

### 提醒与小组件
- 课前提醒：可设提前 0/5/10/15/20/30/45/60 分钟，使用精确闹钟（Android 12+ 会引导授权）
- 开机、改时间、改时区、App 升级后自动重排闹钟
- 桌面小组件：今日课程列表（时间 + 课程 + 地点），每 30 分钟自动刷新

### 设置与数据
- 学期开学日期（自动对齐周一）、学期总周数、当前周（自动计算或手动指定）
- 显示周末、淡化非本周、动态取色、深色模式（跟随系统/浅色/深色）
- 节次数量（8~16 节）与逐节时间表（24 小时制）
- 导出课表图片（PNG，可直接发给同学）
- 备份导出 / 导入（JSON，支持覆盖或合并）、清空课程

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `POST_NOTIFICATIONS` | 发送上课提醒（首次启动时申请一次） |
| `SCHEDULE_EXACT_ALARM` | 课程提醒准点触发（未授权时自动降级为近似时间） |
| `RECEIVE_BOOT_COMPLETED` | 重启后重新安排提醒 |
| `VIBRATE` | 提醒振动 |
| `INTERNET` | 仅用于「网页登录抓取」时打开教务系统页面；其余功能全部离线 |

不需要相机权限、不需要存储权限：识图走「复制提示词 + 粘贴 JSON」，选文件走系统文件选择器，导出走「另存为」对话框。
课表数据不会上传到任何服务器。

## 构建

环境：**JDK 17+**（开发用 Temurin 21）、**Android SDK Platform 36** + **Build-Tools 36.0.0**、Gradle 8.13（仓库自带 wrapper）。

```bash
# 1. 指定本机 SDK 路径（此文件不入库）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 需要自己签名的正式包时，准备签名配置（可选，不配置也能编 debug）
cp keystore.properties.example keystore.properties
keytool -genkeypair -v -keystore app/keystore/release.jks -storetype JKS \
        -alias classmate -keyalg RSA -keysize 2048 -validity 10950

# 3. 构建
./gradlew :app:assembleRelease        # 正式包（未配置签名时输出未签名 APK）
./gradlew :app:assembleDebug          # 调试包，包名带 .debug 后缀，可与正式版共存
./gradlew :app:testDebugUnitTest      # 单元测试（21 个用例，全部离线）
```

产物：`app/build/outputs/apk/release/app-release.apk`、`app/build/outputs/apk/debug/app-debug.apk`。

> 依赖仓库默认走阿里云镜像（`maven.aliyun.com`），境外网络可以在 `settings.gradle.kts` 里把这两行删掉或注释掉。
> 仓库内**不含任何签名密钥**，`keystore.properties` 与 `*.jks` 已在 `.gitignore` 中排除。

## 代码结构

```
app/src/main/java/com/classmate/timetable/
├── App.kt                      应用入口：主题模式、通知渠道、重排提醒
├── data/                       Course / Period 模型、SQLite（Db）、设置（Prefs）
├── ui/
│   ├── BaseActivity.kt         统一处理 edge-to-edge 系统栏内边距
│   ├── MainActivity.kt         主页：周切换、下一节课、详情弹窗、导出
│   ├── TimetableView.kt        自绘课表网格（分栏、淡化、点击/长按、渲染位图）
│   ├── CourseEditActivity.kt   新增/编辑课程（冲突检测、颜色、单双周）
│   ├── AiImportActivity.kt     AI 识图导入：提示词、粘贴、预览、批量导入
│   ├── FileImportActivity.kt   教务文件导入：ics/csv/xls/xlsx/json → 预览 → 导入
│   ├── JwWebActivity.kt        内置浏览器登录教务，抓取页面表格
│   ├── SettingsActivity.kt     设置（学期/显示/提醒/节次/备份）
│   └── ParsedCourseAdapter.kt / PeriodAdapter.kt
├── aiimport/                   AiImportParser：提示词 + 容错 JSON 解析 + 文本兜底
├── importer/                   教务文件解析：ICS / CSV / HTML 表格 / XLSX / JSON / 周次规则
├── notify/                     提醒调度（AlarmManager）、通知、开机接收器
├── widget/                     今日课程桌面小组件
└── util/                       色板、备份 JSON、课表图片渲染

app/src/test/java/.../           AiImportParserTest(11) + ScheduleImporterTest(10)
```

技术选型上刻意保守：**Kotlin + 传统 View 体系 + Material Components 3**，不用 Compose、不用 Room、不用 kapt，
SQLite 直接写 `SQLiteOpenHelper`，xlsx / ics / HTML 表格全部自己解析。
结果是构建链极短（无注解处理器）、APK 只有 4.4 MB、任何一台机器 clone 下来都能直接编。

## 已知限制

- 文件导入的准确率取决于教务导出本身的规范程度：格子是纯文本课表最准，图形化 / 合并单元格极复杂的课表可能需要导入后微调；
- 网页抓取依赖课表页是标准 `<table>` 布局；如果学校用 canvas / div 画课表，抓取会抓不到（可用「复制页面文字」交给 AI）；
- 识图准确率取决于所用 AI 的看图能力，不取决于本 App；App 只保证「拿到 JSON 就一定按规则落库」；
- 需要有一个能看图的 AI（手机里的豆包 / 元宝 / 通义都行）；
- 单课表模型（一套学期设置），暂不支持多学期 / 多课表切换；
- 未开启代码混淆（保证功能稳定）。

## 更新记录

### 1.2.1
- 网页抓取改为**默认手机 UA**（原默认桌面 UA），需要时仍可在菜单切「桌面版网页」
- 默认入口改为**北京校区（校部）新教务系统(2024)**，并区分北京 / 保定两套系统
- 新增菜单「教务入口」：北京新教务 / 数字华电门户 / 北京旧版教务(查询) / 保定校区教务处，一键切换

### 1.2.0
- 新增「教务导入」：`.ics` / `.csv` / `.xls`（HTML 表格） / `.xlsx` / `.json` 选文件导入，纯本地解析
  - 自带 ICS 解析（含 RRULE 周次推算）、HTML 表格解析（rowspan/colspan 展开）、迷你 XLSX 读取（解 zip + sharedStrings）、CSV 分隔符嗅探、编码自动识别
- 新增「网页登录抓取」：内置 WebView 登录教务系统，把当前页面表格抓回来解析
- 新增 11 个解析单测

### 1.1.0
- **移除本地 OCR（ML Kit）与整套网格解析**，改为「复制提示词给 AI → 粘贴返回的 JSON → 预览导入」
- 新增 `aiimport/AiImportParser`：抠 JSON、中英文字段名、星期/节次/周次多种写法、缺字段默认值、越界裁剪、去重、纯文本兜底
- 移除 ML Kit 依赖与原生库：APK 由 24 MB 降到约 2 MB，且 arm32 / arm64 / x86 都能装

### 1.0.1
- 修复 Android 15/16 上（targetSdk 36 强制 edge-to-edge）顶部工具栏被状态栏压住的问题：所有页面统一处理系统栏 / 刘海 / 输入法内边距
- 设置新增「节次数量」（8~16 节）

## License

[MIT](LICENSE)
