# 电视本地播放器
 在电视上浏览电脑硬盘并播放视频文件


目前只支持android5.0以上电视。

包含python + mediamtx媒体服务器；windows c++服务端+android电视端；android电视端包含RemoteLogcatViewer调试工具和TelnetLike服务，可以在pc上远程查看logcat信息和通过telnet运行shell命令。

windows服务端使用ffmpeg解码，具体版本为BtbN编译的ffmpeg-n6.1-latest-win64-gpl-shared-6.1.zip，下载地址https://github.com/BtbN/FFmpeg-Builds/releases。

# 运行环境
python版本：Python 3.10.6

# 编译环境

Android Studio Ladybug Feature Drop | 2024.2.2

Windows 10.0

Memory: 4092M

Cores: 8


Microsoft Visual Studio Community 2019

版本 16.8.6

Visual C++ 2019   00435-60000-00000-AA696

Microsoft Visual C++ 2019


# 编译方法

android:

用android studio打开adnroid目录编译

pc端:

用Visual Studio2019打开windows/c++/TVServer/TVServer.sln编译

# 使用方法

1、电脑和电视连接在同一个wifi，保证在同一个局域网下；

2、打开TVServer.exe；

3、电视上安装android\tv\build\outputs\apk\debug\tv-debug.apk并运行。







