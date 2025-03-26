# 最低Android版本（与minSdkVersion一致）
APP_PLATFORM := android-21

# 支持的ABI
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64

# STL配置
APP_STL := c++_shared

# 编译优化
APP_OPTIM := release

# C++标准
APP_CPPFLAGS := -std=c++17 -fexceptions -frtti