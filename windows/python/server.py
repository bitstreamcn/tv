import socket
import json
import os
import subprocess
import signal
import sys
import string
from pathlib import Path
import re

class FileServer:
    def __init__(self, host='0.0.0.0', port=9999):
        self.host = host
        self.port = port
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # 设置 socket 选项，允许地址重用
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind((self.host, self.port))
        self.server.listen(5)
        self.current_process = None
        self.running = True
        self.client_sockets = set()  # 存储所有客户端连接
        self.video_clients = {}  # 用于跟踪哪个客户端正在播放视频
        print(f"服务器启动在 {host}:{port}")

    def cleanup(self):
        print("\n正在关闭服务器...")
        self.running = False
        
        # 停止所有视频流
        for client_address in list(self.video_clients.keys()):
            self.stop_client_video(client_address)
        
        # 关闭所有客户端连接
        if self.client_sockets:
            print(f"正在关闭 {len(self.client_sockets)} 个客户端连接...")
            for client_socket in self.client_sockets:
                try:
                    client_socket.close()
                except:
                    pass
            self.client_sockets.clear()

        # 关闭服务器socket
        try:
            self.server.shutdown(socket.SHUT_RDWR)
        except:
            pass
        self.server.close()
        print("服务器已关闭")
        sys.exit(0)

    def get_drives(self):
        if os.name == 'nt':  # Windows系统
            drives = []
            for letter in string.ascii_uppercase:
                drive = f"{letter}:\\"
                if os.path.exists(drive):
                    drives.append({
                        'name': f"驱动器 ({drive})",
                        'type': 'drive',
                        'path': drive
                    })
            return {'status': 'success', 'items': drives, 'current_path': 'drives'}
        else:  # Unix/Linux系统
            return self.get_directory_content('/')

    def get_directory_content(self, path='.'):
        # 如果是特殊路径'drives'，返回所有驱动器
        if path == 'drives':
            return self.get_drives()
            
        items = []
        try:
            for item in os.listdir(path):
                full_path = os.path.join(path, item)
                item_type = 'directory' if os.path.isdir(full_path) else 'file'
                items.append({
                    'name': item,
                    'type': item_type,
                    'path': os.path.abspath(full_path)
                })
            return {'status': 'success', 'items': items, 'current_path': os.path.abspath(path)}
        except Exception as e:
            return {'status': 'error', 'message': str(e)}

    def get_video_duration(self, video_path):
        try:
            cmd = [
                'ffprobe',
                '-v', 'error',
                '-show_entries', 'format=duration',
                '-of', 'default=noprint_wrappers=1:nokey=1',
                video_path
            ]
            result = subprocess.run(cmd, capture_output=True, text=True)
            duration = float(result.stdout.strip())
            return duration
        except Exception as e:
            print(f"获取视频时长失败: {e}")
            return 0

    def stream_video(self, video_path, start_time=0, client_address=None):
        try:
            # 如果这个客户端之前在播放视频，先停止之前的进程
            if client_address in self.video_clients:
                old_process = self.video_clients[client_address]
                if old_process and old_process.poll() is None:
                    old_process.terminate()
                    old_process.wait()
                del self.video_clients[client_address]

            duration = self.get_video_duration(video_path)
            
            cmd = [
                'ffmpeg',
                '-re',
                '-stream_loop', '-1',
                '-ss', str(start_time),
                '-i', video_path,
                '-c:v', 'libx264',
                '-preset', 'fast',
                '-tune', 'zerolatency',
                '-crf', '23',
                '-c:a', 'aac',
                '-b:a', '192k',
                '-f', 'rtsp',
                '-rtsp_transport', 'tcp',
                'rtsp://localhost:8554/tvstream'
            ]
            
            process = subprocess.Popen(cmd)
            if client_address:
                self.video_clients[client_address] = process
            
            return {
                'status': 'success',
                'message': '视频流已启动',
                'duration': duration
            }
        except Exception as e:
            return {'status': 'error', 'message': str(e)}

    def stop_client_video(self, client_address):
        if client_address in self.video_clients:
            process = self.video_clients[client_address]
            if process and process.poll() is None:
                print(f"停止客户端 {client_address} 的视频流")
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
            del self.video_clients[client_address]

    def handle_client(self, client_socket, client_address):
        self.client_sockets.add(client_socket)
        current_path = '.'
        while self.running:
            try:
                data = b''
                while True:
                    chunk = client_socket.recv(4096)
                    if not chunk:
                        return
                    data += chunk
                    if b'\n' in data:
                        break
                
                command = json.loads(data.decode('utf-8').strip())
                
                print(f"\n来自 {client_address} 的命令:")
                if command['action'] == 'list':
                    print(f"→ 列出目录: {command.get('path', current_path)}")
                elif command['action'] == 'stream':
                    print(f"→ 播放视频: {command['path']}")
                    if 'start_time' in command:
                        print(f"  从 {command['start_time']:.2f} 秒开始播放")
                elif command['action'] == 'stop_stream':
                    print(f"→ 停止视频播放")
                
                if command['action'] == 'list':
                    current_path = command.get('path', current_path)
                    response = self.get_directory_content(current_path)
                elif command['action'] == 'stream':
                    start_time = command.get('start_time', 0)
                    response = self.stream_video(command['path'], start_time, client_address)
                elif command['action'] == 'stop_stream':
                    self.stop_client_video(client_address)
                    response = {'status': 'success', 'message': '视频流已停止'}
                
                # 打印响应状态
                if response['status'] == 'success':
                    print(f"✓ 命令执行成功")
                else:
                    print(f"✗ 命令执行失败: {response.get('message', '未知错误')}")
                
                # 发送响应
                response_data = json.dumps(response).encode('utf-8') + b'\n'
                chunk_size = 4096
                for i in range(0, len(response_data), chunk_size):
                    chunk = response_data[i:i + chunk_size]
                    client_socket.send(chunk)
            
            except Exception as e:
                print(f"处理客户端 {client_address} 的命令时发生错误: {e}")
                break
        
        # 客户端断开连接时停止其视频流
        self.stop_client_video(client_address)
        self.client_sockets.remove(client_socket)
        client_socket.close()
        print(f"\n客户端 {client_address} 断开连接")

    def run(self):
        # 设置信号处理
        signal.signal(signal.SIGINT, lambda signum, frame: self.cleanup())
        signal.signal(signal.SIGTERM, lambda signum, frame: self.cleanup())

        # 设置socket超时，以便能够定期检查running标志
        self.server.settimeout(1.0)

        print("\n等待客户端连接...")
        while self.running:
            try:
                client, addr = self.server.accept()
                print(f"\n新客户端连接: {addr}")
                # 创建新线程处理客户端连接
                import threading
                client_thread = threading.Thread(
                    target=self.handle_client,
                    args=(client, addr),  # 传递客户端地址
                    daemon=True
                )
                client_thread.start()
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"接受连接时发生错误: {e}")

if __name__ == '__main__':
    server = FileServer()
    try:
        server.run()
    except KeyboardInterrupt:
        server.cleanup() 
    server.run() 