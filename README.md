# demo

#### 介绍
直接使用GraalVM打包的JavaFX项目的演示项目

#### 软件架构
        本项目是一个JavaFX演示项目，主要用于演示如何使用GraalVM打包成本地镜像文件。
        本演示项目只考虑windows系统使用MSVC时的情况。打包的结果是一个单独exe文件。
        本项目与以往的使用GluonFX的插件不同，直接使用GraalVM，因为使用GluonFX打包JavaFX25和GraalVM25时会报错，17不报错。
        与GluonFX的插件不同，本项目打包可以使用cmd，不用打开vs的cmd窗口。

        本项目的代码只是idea创建JavaFX时的默认代码。
        本项目的打包思路为：
            1.打包成不含依赖的jar包
            2.利用maven-assembly-plugin插件将第一步的jar包与依赖的jar包打包成含有当前代码编译结果与所有依赖的jar包
            3.使用exec-maven-plugin插件收集项目整体需要的资源与反射信息，故而打包时运行JavaFX项目的UI界面，需要手动点击所有功能以便插件收集信息。
              收集到的信息被放在target/native/agent-output/main/reachability-metadata.json,可以将此文件保存下来放在src/main/resources/META-INF/native-image/reachability-metadata.json，
              以减少下次编译时收集资源与反射信息的时间，不用点击全部功能。两个文件会自动合并，不用担心冲突。
            4.使用native-maven-plugin插件，其利用GraalVM的native-image将第二步的结果结合第三步的结果编译成exe文件。本地镜像打包相关配置在该插件的configuration标签内配置。
        打包命令为mvn clean -Pnative package。

        我本地测试，用这个办法打包结果大小为44.9MB，比使用GluonFX（JavaFX17和GraalVM17）的打包的六十多兆要小，内存占用也要更小，可能是因为GraalVM的进步吧。
        收集资源与反射信息时会抛出异常，实测不影响打包。
        用这个方法打包出来的jar是未命名模块，不知道为什么，没有解决（Unsupported JavaFX configuration: classes were loaded from 'unnamed module @2fefd6c2'）。


#### 安装教程

1.  xxxx
2.  xxxx
3.  xxxx

#### 使用说明

1.  xxxx
2.  xxxx
3.  xxxx

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


一、七月份工作进度
    １、完成预约相关数据库设计
    ２、完成预约框架搭建
    ３、完成预约小程序及管理端主要编码工作
    ４、完成已知bug修复

二、八月工作计划
    １、人脸设备对接
    ２、预约相关bug修复
    ３、部署预约线上运行环境、发布小程序正式版本
    ４、预约相关财务报表相关功能
    ５、阳光跑相关业务梳理、数据库设计
    ６、阳光跑项目框架搭建、基础功能编码，包括阳光跑app界面开发及管理端界面