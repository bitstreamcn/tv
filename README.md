# 电视本地播放器
 在电视上浏览电脑硬盘并播放视频文件


目前只支持android5.0以上电视。

包含python + mediamtx媒体服务器；windows c++服务端+android电视端；android电视端包含RemoteLogcatViewer调试工具和TelnetLike服务，可以在pc上远程查看logcat信息和通过telnet运行shell命令。

windows服务端使用ffmpeg解码，具体版本为BtbN编译的ffmpeg-n6.1-latest-win64-gpl-shared-6.1.zip，下载地址https://github.com/BtbN/FFmpeg-Builds/releases。

# 视频质量调节

如果默认视频质量无法满足要求，想看更高清质量的，可以调以下参数。

windows/c++/TVServer/media.cpp

找到下面代码

    std::string ffmpegCommand = "ffmpeg -loglevel quiet -ss " + std::to_string((uint32_t)seek_target_ / 1000000) + " -i \"" + pathgb2312 + "\" -c:v libx264 -preset faster -tune zerolatency -maxrate 1.5M -b:v 1.5M -c:a aac -b:a 160k -f mpegts -flush_packets 0 -mpegts_flags resend_headers pipe:1";

修改preset参数和tune参数

tune影响解码编码质量，可以为 film/animation/grain/stillimage/psnr/ssim/fastdecode/zerolatency，其中film质量最高，zerolatency质量最低；

preset影响解码编码速度，可以为 ultrafast/superfast/veryfast/faster/fast/medium/slow/slower/veryslow，ultrafast速度最快，veryslow速度最慢；

根据电脑性能设置上面两个参数

maxrate参数设置帧率，根据网络速度设置。

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

2、打开windows/c++/TVServer/x64/Debug/TVServer.exe；

3、电视上安装android\tv\build\outputs\apk\debug\tv-debug.apk并运行。

# 查看调试信息

浏览器打开android/RemoteLogcatViewer/index.html，输入电视IP地址，如：

ws://192.168.2.168:11229/logcat

点open，就可以看到logcat信息了。

# 远程运行shell

打开xshell，协议选择telnet，输入电视IP地址，如：192.168.2.168，端口号：12345；设置终端，换行符-》收到-》选择LF，点击连接。

连接上以后就可以远程运行shell命令了。














