#!/usr/bin/env bash

# Shadow 的 AGP 兼容性测试已收敛到 AGP 9.x，统一使用 JDK 17。

JAVA_MAJOR_VERSION=$(javap -verbose java.lang.Object | grep "major version" | cut -d " " -f5)
if [[ $JAVA_MAJOR_VERSION -ne 61 ]]; then
  echo "需要JDK 17!"
  exit 1
fi

source ./test_prepare.sh

# 测试版本来源
# AGP release页面：https://developer.android.com/studio/releases/gradle-plugin
# AGP Maven仓库：https://mvnrepository.com/artifact/com.android.tools.build/gradle
# Gradle下载：https://services.gradle.org/distributions/
setGradleVersion 9.3.1
testUnderAGPVersion 9.1.0
