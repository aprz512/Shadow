## core.gradle-plugin模块的AGP各版本黑盒测试

准备一个桩工程`stub-project`，通过命令行参数控制其 AGP 版本和 Shadow 版本。

当前仅支持测试 AGP 9.x。

自动化测试脚本: `test_JDK17.sh`。脚本会先编译 Shadow，发布到本地 Maven，然后用这个 Shadow 版本进行测试。

注意脚本会echo出执行的命令，如果遇到测试失败，可复制命令手工重新执行。

`test_JDK17.sh`需要 JDK 17 环境。

`test_clean.sh`可以还原测试脚本对Gradle文件对改动。

### 确定实际使用的AGP版本：

查看`stub-project/build/intermediates/app_metadata/pluginDebug/app-metadata.properties`
