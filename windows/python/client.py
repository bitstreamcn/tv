import socket
import json
import os
import tkinter as tk
from tkinter import ttk
from tkinter import messagebox
import time
import threading

class FileClient:
    def __init__(self, host='localhost', port=9999):
        self.host = host
        self.port = port
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.current_path = None
        self.is_at_drives = False
        
        # GUI设置
        self.root = tk.Tk()
        self.root.title("文件浏览器")
        self.root.geometry("800x600")
        
        # 创建树形视图
        self.tree = ttk.Treeview(self.root, columns=('类型', '路径'))
        self.tree.heading('#0', text='名称')
        self.tree.heading('类型', text='类型')
        self.tree.heading('路径', text='路径')
        self.tree.pack(fill=tk.BOTH, expand=True)
        
        # 返回上级目录按钮
        self.back_button = tk.Button(self.root, text="返回上级目录", command=self.go_up)
        self.back_button.pack()
        
        # 绑定双击事件
        self.tree.bind('<Double-1>', self.on_double_click)
        
        # 创建进度条框架
        self.progress_frame = ttk.Frame(self.root)
        self.progress_frame.pack(fill=tk.X, padx=5, pady=5)
        
        # 创建进度条
        self.progress_var = tk.DoubleVar()
        self.progress = ttk.Scale(
            self.progress_frame,
            from_=0,
            to=100,
            orient=tk.HORIZONTAL,
            variable=self.progress_var
        )
        # 绑定进度条释放事件
        self.progress.bind("<ButtonRelease-1>", self.on_progress_release)
        self.progress.pack(fill=tk.X, expand=True, side=tk.LEFT)
        
        # 显示时间的标签
        self.time_label = ttk.Label(self.progress_frame, text="00:00:00 / 00:00:00")
        self.time_label.pack(side=tk.LEFT, padx=5)
        
        # 控制按钮
        self.control_frame = ttk.Frame(self.root)
        self.control_frame.pack(fill=tk.X, padx=5, pady=5)
        
        self.play_button = ttk.Button(self.control_frame, text="播放", command=self.play_video)
        self.play_button.pack(side=tk.LEFT, padx=5)
        
        self.stop_button = ttk.Button(self.control_frame, text="停止", command=self.stop_video)
        self.stop_button.pack(side=tk.LEFT, padx=5)
        
        # 视频控制变量
        self.current_video_path = None
        self.video_duration = 0
        self.is_playing = False
        self.update_timer = None
        
        # 添加新的变量来跟踪拖动状态
        self.is_dragging = False
        self.last_progress_update = 0

    def connect(self):
        try:
            self.socket.connect((self.host, self.port))
            # 启动时显示驱动器列表
            self.list_directory('drives')
            self.is_at_drives = True
        except Exception as e:
            messagebox.showerror("错误", f"连接失败: {e}")
            self.root.quit()

    def send_command(self, command):
        try:
            # 发送命令
            command_data = json.dumps(command).encode('utf-8') + b'\n'
            self.socket.send(command_data)
            
            # 接收响应
            data = b''
            while True:
                chunk = self.socket.recv(4096)
                if not chunk:
                    break
                data += chunk
                if b'\n' in data:
                    break
            
            if data:
                return json.loads(data.decode('utf-8').strip())
            else:
                raise ConnectionError("连接已断开")
            
        except Exception as e:
            messagebox.showerror("错误", f"通信错误: {e}")
            return {'status': 'error', 'message': str(e)}

    def list_directory(self, path):
        try:
            response = self.send_command({'action': 'list', 'path': path})
            if response and response.get('status') == 'success':
                self.current_path = response['current_path']
                self.update_tree(response['items'])
                
                # 更新窗口标题显示当前路径
                if path == 'drives':
                    self.root.title("文件浏览器 - 我的电脑")
                else:
                    self.root.title(f"文件浏览器 - {self.current_path}")
            else:
                error_msg = response.get('message', '未知错误') if response else '无响应'
                messagebox.showerror("错误", error_msg)
        except Exception as e:
            messagebox.showerror("错误", f"列出目录失败: {e}")

    def update_tree(self, items):
        for item in self.tree.get_children():
            self.tree.delete(item)
        
        for item in items:
            icon = '🖴' if item['type'] == 'drive' else '📁' if item['type'] == 'directory' else '📄'
            self.tree.insert('', 'end', text=f"{icon} {item['name']}", 
                           values=(item['type'], item['path']))

    def go_up(self):
        if self.current_path:
            if os.name == 'nt':  # Windows系统
                # 如果当前路径是根目录（如 C:\），则返回到驱动器列表
                if len(self.current_path) <= 3:  # 处理类似 "C:\" 的情况
                    self.list_directory('drives')
                    self.is_at_drives = True
                else:
                    parent_path = os.path.dirname(self.current_path)
                    self.list_directory(parent_path)
                    self.is_at_drives = False
            else:  # Unix/Linux系统
                if self.current_path == '/':
                    return  # 已经在根目录，不能再往上
                parent_path = os.path.dirname(self.current_path)
                self.list_directory(parent_path)

    def format_time(self, seconds):
        # 确保seconds是数字
        try:
            seconds = float(seconds)
        except (TypeError, ValueError):
            seconds = 0

        hours = int(seconds // 3600)
        minutes = int((seconds % 3600) // 60)
        secs = int(seconds % 60)
        return f"{hours:02d}:{minutes:02d}:{secs:02d}"

    def on_progress_release(self, event):
        if self.is_playing and self.current_video_path:
            current_time = (self.progress_var.get() / 100) * self.video_duration
            self.play_video(start_time=current_time)

    def update_progress(self):
        if self.is_playing:
            current_value = self.progress_var.get()
            if current_value < 100:
                # 计算每次更新增加的进度值 (基于视频总长度)
                increment = (0.1 / self.video_duration) * 100
                new_value = current_value + increment
                self.progress_var.set(min(new_value, 100))
                
                # 更新时间显示
                current_time = (new_value / 100) * self.video_duration
                self.time_label.config(
                    text=f"{self.format_time(current_time)} / {self.format_time(self.video_duration)}"
                )
            else:
                # 到达结尾时重置到开始
                self.progress_var.set(0)
                
            self.update_timer = self.root.after(100, self.update_progress)

    def play_video(self, start_time=None):
        if self.current_video_path:
            if start_time is None:
                start_time = (self.progress_var.get() / 100) * self.video_duration
            
            response = self.send_command({
                'action': 'stream',
                'path': self.current_video_path,
                'start_time': start_time
            })
            
            if response['status'] == 'success':
                self.is_playing = True
                self.video_duration = response['duration']
                # 更新总时长显示
                self.time_label.config(
                    text=f"{self.format_time(start_time)} / {self.format_time(self.video_duration)}"
                )
                if self.update_timer:
                    self.root.after_cancel(self.update_timer)
                self.update_progress()
            else:
                messagebox.showerror("错误", response['message'])

    def stop_video(self):
        if self.is_playing:
            response = self.send_command({'action': 'stop_stream'})
            if response['status'] == 'success':
                self.is_playing = False
                if self.update_timer:
                    self.root.after_cancel(self.update_timer)
                self.progress_var.set(0)
                self.time_label.config(text="00:00:00 / 00:00:00")

    def on_double_click(self, event):
        item = self.tree.selection()[0]
        item_type = self.tree.item(item)['values'][0]
        path = self.tree.item(item)['values'][1]
        
        if item_type in ['directory', 'drive']:
            # 如果当前正在播放视频，先停止它
            if self.is_playing:
                self.stop_video()
            self.list_directory(path)
            self.is_at_drives = False
        elif path.lower().endswith(('.mp4', '.avi', '.mkv', '.mov')):
            # 如果当前正在播放视频，先停止它
            if self.is_playing:
                self.stop_video()
            self.current_video_path = path
            self.progress_var.set(0)
            self.play_video(start_time=0)

    def run(self):
        try:
            self.connect()
            self.root.mainloop()
        finally:
            # 确保在关闭窗口时停止视频
            if self.is_playing:
                self.stop_video()
            self.socket.close()

if __name__ == '__main__':
    client = FileClient()
    client.run() 