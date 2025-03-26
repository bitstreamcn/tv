LOCAL_PATH := $(call my-dir)

# 清理旧变量
include $(CLEAR_VARS)

# 模块配置
LOCAL_MODULE    := native-lib          # 与build.gradle中的ndk.moduleName一致
LOCAL_SRC_FILES := \
    TcpSocket.cpp \
    TlvProtocol.cpp \
    TcpClient.cpp \
    TcpDataSource.cpp \
    CircularBuffer.cpp \
    native-lib.cpp

# 包含路径（根据实际头文件位置调整）
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include

# 编译标志
LOCAL_CFLAGS += -std=c++17 -DANDROID -Wall
LOCAL_CPPFLAGS += -fexceptions -frtti

# 链接库
LOCAL_LDLIBS := -llog -landroid
LOCAL_SHARED_LIBRARIES := 

# 构建共享库
include $(BUILD_SHARED_LIBRARY)

# 如果需要其他模块可继续添加
# include $(CLEAR_VARS)
# ...