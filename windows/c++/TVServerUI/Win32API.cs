using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace TVServerUI
{
    using System.Runtime.InteropServices;

    public class Win32API
    {
        [DllImport("shell32.dll", CharSet = CharSet.Auto)]
        public static extern IntPtr ShellExecute(
            IntPtr hwnd,         // 父窗口句柄（WPF中用IntPtr.Zero）
            string lpOperation,  // 操作类型（如"open"、"print"）
            string lpFile,       // 文件/程序路径 
            string lpParameters, // 命令行参数 
            string lpDirectory,  // 工作目录 
            int nShowCmd         // 窗口显示方式（如SW_HIDE隐藏窗口）
        );

        // 窗口显示模式常量 
        public const int SW_SHOWNORMAL = 1; // 正常显示 
        public const int SW_HIDE = 0;       // 隐藏窗口 
    }
}
