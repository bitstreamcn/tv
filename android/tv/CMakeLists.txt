cmake_minimum_required(VERSION 3.18.1)

project("tv")

# 设置C++标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 添加native代码库
add_library(
        native-lib
        SHARED
        src/main/cpp/native-lib.cpp
        src/main/cpp/TcpClient.cpp
        src/main/cpp/TcpDataSource.cpp
        src/main/cpp/CircularBuffer.cpp
)

# 查找依赖库
find_library(
        log-lib
        log
)

# 包含目录
include_directories(
        src/main/cpp/include
)

# 链接库
target_link_libraries(
        native-lib
        android
        ${log-lib}
)

# 配置输出路径
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${PROJECT_SOURCE_DIR}/build/intermediates/cmake/debug/obj/${ANDROID_ABI})