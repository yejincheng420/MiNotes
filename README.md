# MiNotes

# 📝 小米便签 (MiNotes) - 开源软件阅读与二次开发实践



本项目是基于 [MiCode/Notes](https://github.com/MiCode/Notes)（小米便签）开源代码的软件工程综合实践项目。



我们团队将十多年前的旧版 Eclipse 架构代码成功迁移至现代 Android Studio (Gradle) 环境，并在此基础上开展代码结构分析、协同注释以及新功能的扩展开发。



## 👥 小组人员与分工

本项目具体分工如下：

| 姓名 | 学号 | 角色 | 主要负责工作 |
| :--- | :--- | :--- | :--- |
| **叶锦城** | 202305502062 | 项目组长 / 开发研发 | 负责项目现代化环境迁移与依赖修复、核心框架梳理、Git仓库搭建、build.gradle配置优化；主导UI层核心模块的代码分析与功能开发。 |
| **毕德淳** | 202305190708 | 新功能开发 | 阅读并分析NotesProvider.java（内容提供者）、Notes.java/Contact.java（数据模型）；在源码基础上开发新功能：便签按颜色分类功能，并负责对应模块的代码实现与调试。 |
| **潘昊麟** | 202305820430 | 开发研发与分析 | 负责 **数据层与GTask同步层** 的代码阅读与分析，包括：NotesDatabaseHelper.java（SQLite数据库）、gtask/remote/GTaskManager.java、gtask/remote/GTaskClient.java（Google Tasks客户端）等云同步模块；参与核心功能开发与代码优化。 |
| **肖鹏祯** | 202305720418 | 文档与统筹 | 负责 **工具层、Widget与Alarm提醒** 模块的代码整理与注释；整合各组员的扩展功能成果；撰写实验报告文档、汇报PPT制作及最终演示准备。 |



## ✨ 扩展新功能

根据课程要求，我们在原版小米便签的基础上，已完成以下新功能的开发：

### 便签按颜色分类筛选

在便签列表界面新增了颜色筛选功能，方便用户快速定位特定颜色的便签。实现细节如下：

- **筛选入口**：在便签列表顶部新增颜色筛选栏，包含「全部」及5种颜色快捷按钮（黄色、蓝色、白色、绿色、红色）
- **交互逻辑**：点击对应颜色按钮后，列表仅显示该颜色的便签；再次点击「全部」可恢复显示所有便签
- **视觉反馈**：选中状态通过透明度变化和文字颜色变化进行区分，提供直观的操作体验
- **底层实现**：通过在SQL查询中添加 `BG_COLOR_ID` 筛选条件，实现高效的数据过滤

该功能在 `NotesListActivity.java` 中实现，UI布局文件为 `note_list.xml`，配套的Drawable资源包括 `filter_btn_bg.xml`、`filter_yellow_bg.xml` 等6个背景文件。

---

## 🛠️ 开发环境与运行指南

由于原始代码过于老旧，本项目已做过深度现代化改造。**请其他组员/老师在拉取代码后，务必按照以下环境运行，以防报错：**



- **IDE:** Android Studio (推荐最新版)
- **构建工具:** Gradle (Groovy DSL)
- **开发语言:** Java



### 💡 核心填坑记录（环境修复指南）

为保证项目能顺利编译，本项目已在底层做了如下适配（组员直接拉取运行即可，无需重复操作）：

1. **网络库修复**：在 `build.gradle` 中启用了 `useLibrary 'org.apache.http.legacy'` 并手动引入了 HttpComponents 基础 Jar 包，同时剔除了冲突的 `httpclient-osgi`。

2. **Android 12+ 适配**：重写了 `AndroidManifest.xml`，为所有包含 `<intent-filter>` 的 Activity 和 Receiver 显式声明了 `android:exported="true"`。

3. **资源丢失修复**：补全了现代 Material 主题要求但旧代码缺失的颜色变量（如 `purple_500` 等），以及部分缺失的字符串定义。

4. **语法过时修复**：将已被弃用的 `Notification.setLatestEventInfo()` 方法重构为现代的 `Notification.Builder` 模式；批量修复了常量表达式 `switch-case` 报错。



## 🚀 组员协作规范 (Git Flow)



1. 克隆项目到本地：`git clone [你的Github仓库地址]`
2. 每次开发前，先拉取最新代码：`git pull origin main`
3. 协同添加注释或开发新功能时，请尽量新建分支：`git checkout -b feature/[你的名字缩写]`
4. 提交代码时说明改动内容：`git commit -m "feat: 新增了XXX功能"` 或 `git commit -m "docs: 添加了NotesListActivity的代码注释"`
5. 推送并合并请求 (Pull Request) 到 main 分支。



---

*本项目仅供学习与课程实验使用。感谢 MiCode 社区提供的开源代码。*