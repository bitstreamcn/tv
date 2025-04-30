using System;
using System.Collections.Generic;
using System.Configuration;
using System.Data;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;

namespace TVServerUI
{
    /// <summary>
    /// App.xaml 的交互逻辑
    /// </summary>
    public partial class App : Application
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            
            // 设置应用程序的未处理异常处理程序
            Current.DispatcherUnhandledException += Current_DispatcherUnhandledException;

            // 预加载系统图标资源
            Task.Run(() =>
            {
                System.Drawing.Icon.ExtractAssociatedIcon(System.Windows.Forms.Application.ExecutablePath);
            });
        }

        private void Current_DispatcherUnhandledException(object sender, System.Windows.Threading.DispatcherUnhandledExceptionEventArgs e)
        {
            MessageBox.Show($"发生未处理的异常: {e.Exception.Message}", "错误", 
                          MessageBoxButton.OK, MessageBoxImage.Error);
            e.Handled = true;
        }
    }
}
