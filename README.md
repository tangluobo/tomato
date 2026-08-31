# Tomato

JavaFX 数据库、服务器与远程桌面工具，支持普通 JAR、jpackage 安装包和 GraalVM Native Image AOT 构建。

## Maven Profile 打包

在 IntelliJ IDEA 的 Maven 工具窗口展开 **Profiles**，勾选需要的 Profile 后运行 Lifecycle 中的 `clean`、`package` 即可。命令行与 IDE 的行为完全相同。

### 默认：普通 JAR

不勾选任何 Profile：

```shell
mvn clean package
```

默认构建不会启动应用、不会运行 tracing agent、也不会执行 Native Image。产物位于：

- `target/tomato-${project.version}.jar`：普通 JAR。
- `target/tomato-${project.version}-jar-with-dependencies.jar`：包含依赖的可运行 JAR。

### `native`：交互采集并构建 AOT（推荐）

勾选 `native`，然后运行 `package`：

```shell
mvn clean package -Pnative
```

构建到 `test` 阶段时会自动打开 JVM 版应用。手动点击需要支持 AOT 的页面和功能，然后正常关闭应用窗口。Maven 会自动合并刚采集的元数据、刷新构建目录，并继续构建当前操作系统的原生程序：

- Windows：`target/tomato.exe`，并保留同目录下生成的 DLL。
- Linux/macOS：`target/tomato`，并保留同目录下生成的动态库（如果 GraalVM 输出了动态库）。
- 所有平台都会把主程序、动态库、`README.md` 和 `LICENSE` 整理到
  `target/native-package/tomato-${project.version}-<os>-<arch>/`。
- 同时在 `dist/` 自动生成 ZIP 和 `tar.gz`，不再需要手动复制依赖文件。

### `native-direct`：跳过采集直接构建

已经完成过采集、只想快速重新打包时，勾选 `native-direct`：

```shell
mvn clean package -Pnative-direct
```

此模式不会打开应用，直接使用仓库中已有的 `src/main/resources/META-INF/native-image` 配置执行 AOT。原生链接结束后同样会自动生成完整的 ZIP 和 `tar.gz` 发行包。

### `aot-agent`：交互采集并自动合并

勾选 `aot-agent`，然后运行 `package`：

```shell
mvn clean package -Paot-agent
```

构建到 `test` 阶段时会自动打开 JVM 版应用。依次进入需要支持 AOT 的页面、点击相关功能，覆盖反射、资源、JNI、动态代理、序列化等运行路径。操作结束后请**正常关闭应用窗口**，Maven 会继续执行并自动完成以下工作：

1. 将采集结果写入 `target/native/agent-output/main`。
2. 通过 `native:metadata-copy` 合并到 `src/main/resources/META-INF/native-image`。
3. 保留已有配置，新增内容采用 `merge=true` 合并。

此 Profile 只采集和合并，不执行耗时的 Native Image 编译。合并完成后应查看 Git diff，并提交需要保留的元数据。

## Windows、Linux、macOS

Native Image 不做跨平台交叉编译：Windows 产物必须在 Windows 构建，Linux 产物必须在 Linux 构建，macOS 产物必须在 macOS 构建。三个平台都使用相同的 `native`、`native-direct`、`aot-agent` Profile。

- Windows：安装 GraalVM JDK 和 Visual Studio C++ Build Tools/MSVC。
- Linux：安装 GraalVM JDK、GCC/Clang、glibc 开发包及 JavaFX/GTK 所需系统库。
- macOS：安装 GraalVM JDK 和 Xcode Command Line Tools。

建议分别在三个平台运行一次 `aot-agent`，把各平台实际触发的 JNI/资源元数据合并进仓库，再构建该平台的最终产物。

原有 `windows_exe`、`windows_msi`、`windows_image`、`linux_*`、`macos_*` Profile 属于 jpackage/JVM 发行包，不是 GraalVM AOT；不要与 Native Profile 混淆。

## AOT 自动发行包

本地执行以下命令即可完成 AOT 编译、依赖收集和双格式压缩：

```shell
mvn clean package -Pnative-direct
```

产物名统一采用 `tomato-<version>-<platform>-<arch>.<format>`。以 1.0.2 Windows x86_64 为例：

- `dist/tomato-1.0.2-windows-x86_64.zip`
- `dist/tomato-1.0.2-windows-x86_64.tar.gz`

ZIP 与 `tar.gz` 内都有一个同名顶层目录，包含 AOT 主程序及其全部同目录动态库。`mvn clean` 会同时清理旧的 `target/` 与 `dist/`，避免不同版本的文件混入新包。

`.github/workflows/maven-publish.yml` 会在下列原生 runner 上并行编译；每个平台/架构只编译一次 AOT，再从同一份 AOT 目录派生所有格式：

| 平台 | 架构 | 发行格式 |
|------|------|----------|
| Windows | x86_64 | ZIP、tar.gz、NSIS EXE、MSI |
| Linux | x86_64 | ZIP、tar.gz、DEB、RPM、AppImage |
| Linux | arm64 | ZIP、tar.gz、DEB、RPM、AppImage |
| macOS | x86_64 | ZIP、tar.gz、DMG、PKG |
| macOS | arm64 | ZIP、tar.gz、DMG、PKG |

推送 `v*` 标签会自动构建并创建 GitHub Release，也可以在 Actions 页面手动运行。手动填写的版本或标签版本必须与 `pom.xml` 的项目版本一致。Release 会附带所有产物及 `SHA256SUMS.txt`。

当前 Windows 和 macOS 安装包未做代码签名；分发给其他用户时，系统可能显示未知开发者提示。

## GraalVM 选择

项目当前使用 JavaFX 27 EA（class file version 69），构建和测试必须使用 JDK/GraalVM 25 或更高版本。确保 `JAVA_HOME` 指向 GraalVM，并让其 `bin` 位于 `PATH` 前部。例如 Windows PowerShell：

```powershell
$env:JAVA_HOME = 'D:\App\Java\graalvm-jdk-25.0.3+9.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn clean package -Pnative
```

可先执行以下命令确认环境：

```shell
java -version
native-image --version
mvn -version
```

#### 参与贡献

1.  Fork 本仓库
2.  新建 Feat_xxx 分支
3.  提交代码
4.  新建 Pull Request

# 开发计划：
```text
一、基本功能
    1、半选 时里面的方块要上下左右都 居中，现在太偏下了、
    2、复选框中的对号在点丑
    3、备份功能
    4、还原界面和功能
    5、不能任意地方都能拖动界面位置
    6、表重命名功能
    7、查询和打开表后，能根据id删除，更换表样式
    8、新建表，编辑表的功能
    9、更换应用图标
    10、打开表后，数据编辑功能
    11、复制表功能
    12、转为sql功能
    13、导入、导出（全表、查询结果）
二、windows rdp远程实现
三、S3（ALIYUN_OSS/MINIO）实现
四、SFTP、FTP实现
五、增加本地终端连接（不同平台不同实现，调用本地命令行）
六、Redis连接实现
```



1.  使用 Readme\_XXX.md 来支持不同的语言，例如 Readme\_en.md, Readme\_zh.md
2.  Gitee 官方博客 [blog.gitee.com](https://blog.gitee.com)
3.  你可以 [https://gitee.com/explore](https://gitee.com/explore) 这个地址来了解 Gitee 上的优秀开源项目
4.  [GVP](https://gitee.com/gvp) 全称是 Gitee 最有价值开源项目，是综合评定出的优秀开源项目
5.  Gitee 官方提供的使用手册 [https://gitee.com/help](https://gitee.com/help)
6.  Gitee 封面人物是一档用来展示 Gitee 会员风采的栏目 [https://gitee.com/gitee-stars/](https://gitee.com/gitee-stars/)


踩坑：
１、五笔输入法在linux下无法上屏：从上面版本换成下面的版本，直接好了
```xml
<!--        <javafx.version>25.0.3-ea+3</javafx.version>-->
        <javafx.version>27-ea+24</javafx.version>
```
