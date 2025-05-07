using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Navigation;
using System.Drawing;
using Forms = System.Windows.Forms;
using Microsoft.Win32;
using System.Threading.Tasks;
using System.Windows.Threading;
using System.Net.Http;
using System.Windows.Media.Animation;
using System.Linq;

namespace TVServerUI
{
    /// <summary>
    /// MainWindow.xaml 的交互逻辑
    /// </summary>
    public partial class MainWindow : Window
    {
        private ObservableCollection<SmbEntry> smbEntries = new ObservableCollection<SmbEntry>();
        private readonly string smbFilePath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "smb.json");
        private Process tvServerProcess;
        private Button startButton;
        private Button stopButton;
        private Forms.NotifyIcon notifyIcon;
        private bool isClosing = false;
        private const string StartupKey = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run";
        private const string AppName = "TVServerUI";
        private const string AutoStartParameter = "--autostart";

        private const string BingImageApi = "https://cn.bing.com/HPImageArchive.aspx?format=js&idx={0}&n=1";
        private const string BingImageBaseUrl = "https://cn.bing.com";
        private DispatcherTimer wallpaperTimer;
        private int currentImageIndex = 0;
        private bool isInitialWallpaperLoaded = false;

        private const int MaxLogLines = 1000;
        private const int LogLinesToRemove = 500;

        public MainWindow()
        {
            InitializeComponent();
            
            // 延迟初始化托盘图标
            Dispatcher.BeginInvoke(new Action(async () =>
            {
                await Task.Delay(1000); // 延迟1秒后开始加载
                InitializeTrayIcon();
            }), DispatcherPriority.Background);

            // 延迟加载壁纸
            Dispatcher.BeginInvoke(new Action(async () =>
            {
                await Task.Delay(2000); // 延迟1秒后开始加载
                await InitializeWallpaper();
            }), DispatcherPriority.Background);

            LoadSmbData();
            CheckAutoStartStatus();
            
            // 获取按钮引用
            startButton = FindName("StartButton") as Button;
            stopButton = FindName("StopButton") as Button;
            
            // 初始化按钮状态
            if (startButton != null && stopButton != null)
            {
                stopButton.IsEnabled = false;
                startButton.IsEnabled = true;
            }

            // 检查是否为自动启动
            string[] args = Environment.GetCommandLineArgs();
            if (args.Length > 1 && args[1] == AutoStartParameter)
            {
                Hide();
                StartTVServer();
                startButton.IsEnabled = false;
                stopButton.IsEnabled = true;
            }
        }

        private async Task InitializeWallpaper()
        {
            // 初始化壁纸定时器
            wallpaperTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromMinutes(5)
            };
            wallpaperTimer.Tick += async (s, e) => await LoadNextWallpaper();
            
            // 加载第一张壁纸
            await LoadNextWallpaper();
            wallpaperTimer.Start();
        }

        private async Task LoadNextWallpaper()
        {
            try
            {
                using (var client = new HttpClient())
                {
                    var response = await client.GetStringAsync(string.Format(BingImageApi, currentImageIndex));
                    var bingData = JsonSerializer.Deserialize<BingImageData>(response);
                    var imageUrl = BingImageBaseUrl + bingData.images[0].url;

                    await Task.Run(async () =>
                    {
                        var imageBytes = await client.GetByteArrayAsync(imageUrl);
                        await Dispatcher.InvokeAsync(() =>
                        {
                            var image = new BitmapImage();
                            using (var ms = new MemoryStream(imageBytes))
                            {
                                image.BeginInit();
                                image.CacheOption = BitmapCacheOption.OnLoad;
                                image.StreamSource = ms;
                                image.EndInit();
                                image.Freeze(); // 优化性能
                            }

                            if (!isInitialWallpaperLoaded)
                            {
                                oldImage.Source = image;
                                isInitialWallpaperLoaded = true;
                            }
                            else
                            {
                                newImage.Source = image;
                                var storyboard = (Storyboard)FindResource("BackgroundFadeStoryboard");
                                storyboard.Begin();

                                Task.Delay(1000).ContinueWith(_ =>
                                {
                                    Dispatcher.Invoke(() => oldImage.Source = newImage.Source);
                                });
                            }
                        });
                    });

                    currentImageIndex = (currentImageIndex + 1) % 8;
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"加载壁纸失败: {ex.Message}");
            }
        }

        private void Window_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            DragMove();
        }

        private void MinButton_Click(object sender, RoutedEventArgs e)
        {
            WindowState = WindowState.Minimized;
        }

        private void CloseButton_Click(object sender, RoutedEventArgs e)
        {
            Hide();
        }

        private void InitializeTrayIcon()
        {
            if (notifyIcon != null) return;

            notifyIcon = new Forms.NotifyIcon
            {
                Visible = true,
                Text = "TV Server UI"
            };

            // 异步加载图标
            Task.Run(() =>
            {
                var icon = System.Drawing.Icon.ExtractAssociatedIcon(
                    System.Windows.Forms.Application.ExecutablePath);
                
                Dispatcher.Invoke(() =>
                {
                    notifyIcon.Icon = icon;
                    SetupTrayIconMenu();
                });
            });
        }

        private void SetupTrayIconMenu()
        {
            var contextMenu = new Forms.ContextMenuStrip();
            var showItem = new Forms.ToolStripMenuItem("显示窗口");
            var exitItem = new Forms.ToolStripMenuItem("退出程序");

            showItem.Click += (s, e) => ShowWindow();
            exitItem.Click += (s, e) => ExitApplication();

            contextMenu.Items.Add(showItem);
            contextMenu.Items.Add(new Forms.ToolStripSeparator());
            contextMenu.Items.Add(exitItem);

            notifyIcon.ContextMenuStrip = contextMenu;
            notifyIcon.MouseClick += NotifyIcon_MouseClick;
        }

        private void NotifyIcon_MouseClick(object sender, Forms.MouseEventArgs e)
        {
            if (e.Button == Forms.MouseButtons.Left)
            {
                ShowWindow();
            }
        }

        private void ShowWindow()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
        }

        private void ExitApplication()
        {
            isClosing = true;
            notifyIcon.Dispose();
            Application.Current.Shutdown();
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            if (!isClosing)
            {
                e.Cancel = true;
                Hide();
            }
            base.OnClosing(e);
        }

        protected override void OnStateChanged(EventArgs e)
        {
            if (WindowState == WindowState.Minimized)
            {
                Hide();
            }
            base.OnStateChanged(e);
        }

        private void LoadSmbData()
        {
            if (File.Exists(smbFilePath))
            {
                var json = File.ReadAllText(smbFilePath);
                var entries = JsonSerializer.Deserialize<List<SmbEntry>>(json);
                smbEntries.Clear();
                if (entries != null)
                {
                    foreach (var entry in entries)
                    {
                        smbEntries.Add(entry);
                    }
                }
            }
            smbDataGrid.ItemsSource = smbEntries;
        }

        private void SmbDataGrid_RowEditEnding(object sender, DataGridRowEditEndingEventArgs e)
        {
            SaveSmbData();
        }

        private void AddNewRow_Click(object sender, RoutedEventArgs e)
        {
            smbEntries.Add(new SmbEntry());
            SaveSmbData();
        }

        private void MoveRowUp_Click(object sender, RoutedEventArgs e)
        {
            var row = (sender as FrameworkElement)?.DataContext as SmbEntry;
            if (row != null)
            {
                int index = smbEntries.IndexOf(row);
                if (index > 0)
                {
                    smbEntries.Move(index, index - 1);
                    SaveSmbData();
                }
            }
        }

        private void MoveRowDown_Click(object sender, RoutedEventArgs e)
        {
            var row = (sender as FrameworkElement)?.DataContext as SmbEntry;
            if (row != null)
            {
                int index = smbEntries.IndexOf(row);
                if (index < smbEntries.Count - 1)
                {
                    smbEntries.Move(index, index + 1);
                    SaveSmbData();
                }
            }
        }

        private void DeleteRow_Click(object sender, RoutedEventArgs e)
        {
            if (smbDataGrid.SelectedItem is SmbEntry selectedEntry)
            {
                smbEntries.Remove(selectedEntry);
                SaveSmbData();
            }
        }

        private void SaveSmbData()
        {
            var json = JsonSerializer.Serialize(smbEntries, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(smbFilePath, json);
        }

        private void StartButton_Click(object sender, RoutedEventArgs e)
        {
            StartTVServer();
            startButton.IsEnabled = false;
            stopButton.IsEnabled = true;
            
            // 切换按钮样式
            startButton.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(110, 39, 39));
            startButton.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(238, 213, 183));
            stopButton.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 128, 0));
            stopButton.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(255, 255, 255));
            
            // 切换到运行状态标签页
            tabControl.SelectedIndex = 0;
        }

        private void StopButton_Click(object sender, RoutedEventArgs e)
        {
            StopTVServer();
            startButton.IsEnabled = true;
            stopButton.IsEnabled = false;
            
            // 切换按钮样式
            startButton.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 128, 0));
            startButton.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(255, 255, 255));
            stopButton.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(110, 39, 39));
            stopButton.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(238, 213, 183));
            
            // 切换到运行状态标签页
            tabControl.SelectedIndex = 0;
        }

        private void StartTVServer()
        {
            try
            {
                string exePath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "TVServer.exe");
                tvServerProcess = new Process
                {
                    StartInfo = new ProcessStartInfo
                    {
                        FileName = exePath,
                        UseShellExecute = false,
                        RedirectStandardOutput = true,
                        RedirectStandardError = true,
                        CreateNoWindow = true
                    }
                };

                tvServerProcess.OutputDataReceived += (s, e) =>
                {
                    if (!string.IsNullOrEmpty(e.Data))
                    {
                        AppendLog(e.Data);
                    }
                };

                tvServerProcess.ErrorDataReceived += (s, e) =>
                {
                    if (!string.IsNullOrEmpty(e.Data))
                    {
                        AppendLog("错误: " + e.Data);
                    }
                };

                tvServerProcess.Start();
                tvServerProcess.BeginOutputReadLine();
                tvServerProcess.BeginErrorReadLine();
            }
            catch (Exception ex)
            {
                MessageBox.Show($"启动TVServer失败: {ex.Message}", "错误", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private void StopTVServer()
        {
            if (tvServerProcess != null && !tvServerProcess.HasExited)
            {
                try
                {
                    //tvServerProcess.Kill();
                    //ShellExecute(NULL, "open", "taskkill.exe", ("/PID " + std::to_string(pi.dwProcessId) + " /T /F").c_str(), NULL, SW_HIDE);
                    // 运行批处理脚本（隐藏控制台）
                    Win32API.ShellExecute(
                        IntPtr.Zero,
                        "open",
                        "taskkill.exe",
                        "/PID " + tvServerProcess.Id + " /T /F",  // /C 执行后关闭控制台 
                        null,
                        Win32API.SW_HIDE
                    );
                    tvServerProcess.WaitForExit();
                    AppendLog("TVServer已停止");
                }
                catch (Exception ex)
                {
                    MessageBox.Show($"停止TVServer失败: {ex.Message}", "错误", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
        }

        private void CheckAutoStartStatus()
        {
            using (RegistryKey key = Registry.CurrentUser.OpenSubKey(StartupKey))
            {
                if (key != null)
                {
                    string value = key.GetValue(AppName) as string;
                    autoStartCheckBox.IsChecked = value != null && 
                        value.Equals($"\"{System.Diagnostics.Process.GetCurrentProcess().MainModule.FileName}\" {AutoStartParameter}");
                }
            }
        }

        private void AutoStartCheckBox_Checked(object sender, RoutedEventArgs e)
        {
            SetAutoStart(true);
        }

        private void AutoStartCheckBox_Unchecked(object sender, RoutedEventArgs e)
        {
            SetAutoStart(false);
        }

        private void SetAutoStart(bool enable)
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(StartupKey, true))
                {
                    if (key == null)
                    {
                        MessageBox.Show("无法访问注册表", "错误", MessageBoxButton.OK, MessageBoxImage.Error);
                        return;
                    }

                    if (enable)
                    {
                        string exePath = System.Diagnostics.Process.GetCurrentProcess().MainModule.FileName;
                        key.SetValue(AppName, $"\"{exePath}\" {AutoStartParameter}");
                    }
                    else
                    {
                        key.DeleteValue(AppName, false);
                    }
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"设置开机自启动失败: {ex.Message}", "错误", 
                              MessageBoxButton.OK, MessageBoxImage.Error);
                autoStartCheckBox.IsChecked = !enable;
            }
        }

        private void AppendLog(string message)
        {
            Dispatcher.Invoke(() =>
            {
                logTextBlock.Text += message + Environment.NewLine;
                
                // 检查日志行数
                var lines = logTextBlock.Text.Split('\n');
                if (lines.Length > MaxLogLines)
                {
                    // 保留后面的行
                    logTextBlock.Text = string.Join(Environment.NewLine, 
                        lines.Skip(LogLinesToRemove));
                }
                
                // 自动滚动到底部
                scrollViewer.ScrollToEnd();
            });
        }
    }

    // Bing API 响应数据类
    public class BingImageData
    {
        public BingImage[] images { get; set; }
    }

    public class BingImage
    {
        public string url { get; set; }
    }

    public class SmbEntry : INotifyPropertyChanged
    {
        private string _name;
        private string _ip;
        private string _user;
        private string _password;

        public string name
        {
            get => _name;
            set
            {
                if (_name != value)
                {
                    _name = value;
                    OnPropertyChanged(nameof(name));
                }
            }
        }

        public string ip
        {
            get => _ip;
            set
            {
                if (_ip != value)
                {
                    _ip = value;
                    OnPropertyChanged(nameof(ip));
                }
            }
        }

        public string user
        {
            get => _user;
            set
            {
                if (_user != value)
                {
                    _user = value;
                    OnPropertyChanged(nameof(user));
                }
            }
        }

        public string password
        {
            get => _password;
            set
            {
                if (_password != value)
                {
                    _password = value;
                    OnPropertyChanged(nameof(password));
                }
            }
        }

        public event PropertyChangedEventHandler PropertyChanged;

        protected virtual void OnPropertyChanged(string propertyName)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }
}
