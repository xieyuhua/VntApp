using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;

namespace VntClient
{
    /// <summary>单个在线设备。</summary>
    public class PeerInfo
    {
        public string Name = "";
        public string VirtualIp = "";
        public string Status = "";
        public string Path = "";
        public string Rtt = "";
    }

    /// <summary>当前实例的状态。</summary>
    public class LocalInfo
    {
        public string Name = "";
        public string VirtualIp = "";
        public string Gateway = "";
        public string Netmask = "";
        public string ConnectionStatus = "";
        public string NatType = "";
        public string RelayServer = "";
        public string PublicIps = "";
    }

    /// <summary>
    /// 托管 vnt-cli.exe 子进程。
    ///
    /// <p>vnt-cli 是长驻进程，通过 HTTP 本地接口提供状态查询；
    /// UI 通过运行 vnt-cli --info / --list 等命令读取数据。
    /// </summary>
    public class VntProcess
    {
        private readonly string exePath;
        private Process process;
        private readonly StringBuilder outputLog = new StringBuilder();

        public event Action<string> LogReceived;

        public VntProcess(string exePath)
        {
            this.exePath = exePath;
        }

        public bool IsRunning
        {
            get { return process != null && !process.HasExited; }
        }

        public string DumpLog()
        {
            lock (outputLog) return outputLog.ToString();
        }

        /// <summary>启动组网。需要管理员权限创建虚拟网卡。</summary>
        public void Start(VntConfig config)
        {
            if (IsRunning) throw new InvalidOperationException("组网已在运行");

            var startInfo = new ProcessStartInfo
            {
                FileName = exePath,
                Arguments = config.ToArguments(),
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
                WorkingDirectory = Path.GetDirectoryName(exePath) ?? Environment.CurrentDirectory
            };

            process = new Process { StartInfo = startInfo, EnableRaisingEvents = true };
            process.OutputDataReceived += (s, e) => Append(e.Data);
            process.ErrorDataReceived += (s, e) => Append(e.Data);
            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
        }

        private void Append(string line)
        {
            if (string.IsNullOrEmpty(line)) return;
            lock (outputLog)
            {
                outputLog.AppendLine(line);
                // 避免长时间运行后日志无限增长。
                if (outputLog.Length > 200000) outputLog.Remove(0, 100000);
            }
            var handler = LogReceived;
            if (handler != null) handler(line);
        }

        public void Stop()
        {
            try
            {
                RunQuery("--stop", 5000);
            }
            catch (Exception) { }
            if (process != null)
            {
                try
                {
                    if (!process.HasExited) process.Kill();
                }
                catch (Exception) { }
                finally
                {
                    process.Dispose();
                    process = null;
                }
            }
        }

        public LocalInfo QueryLocalInfo()
        {
            string output = RunQuery("--info", 5000);
            var info = new LocalInfo();
            foreach (string raw in output.Split('\n'))
            {
                string line = raw.Trim();
                int split = line.IndexOf(':');
                if (split <= 0) continue;
                string key = line.Substring(0, split).Trim();
                string value = line.Substring(split + 1).Trim();
                switch (key)
                {
                    case "Name": info.Name = value; break;
                    case "Virtual ip": info.VirtualIp = value; break;
                    case "Virtual gateway": info.Gateway = value; break;
                    case "Virtual netmask": info.Netmask = value; break;
                    case "Connection status": info.ConnectionStatus = value; break;
                    case "NAT type": info.NatType = value; break;
                    case "Relay server": info.RelayServer = value; break;
                    case "Public ips": info.PublicIps = value; break;
                }
            }
            return info;
        }

        public List<PeerInfo> QueryPeers()
        {
            string output = RunQuery("--list", 5000);
            var peers = new List<PeerInfo>();
            string[] lines = output.Split('\n');
            foreach (string raw in lines)
            {
                string line = raw.TrimEnd('\r');
                if (line.Trim().Length == 0) continue;
                if (line.TrimStart().StartsWith("Name")) continue; // 表头
                // 固定宽度列：Name / Virtual Ip / Status / P2P-Relay / Rt
                string[] parts = line.Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length < 3) continue;
                var peer = new PeerInfo
                {
                    Name = parts[0],
                    VirtualIp = parts[1],
                    Status = parts[2]
                };
                if (parts.Length > 3) peer.Path = parts[3];
                if (parts.Length > 4) peer.Rtt = parts[4];
                peers.Add(peer);
            }
            return peers;
        }

        public string QueryRoutes()
        {
            return RunQuery("--route", 5000);
        }


        /// <summary>
        /// 设置 vnt-tun 虚拟网卡的网络类别。
        ///
        /// <p>Windows 默认把新网卡归为"公用"，会限制入站连接与网络发现，
        /// 导致同网段其他设备无法主动访问本机。改为"专用"可解除这些限制。
        /// 每新建一次网卡，Windows 都会生成一个新的连接配置文件，
        /// 因此需要按 InterfaceAlias 批量处理所有匹配的配置文件。
        /// </summary>
        public static bool SetNetworkCategory(string category, out string message)
        {
            if (category != "Private" && category != "Public")
            {
                message = "无效的网络类别";
                return false;
            }
            string script =
                "$ErrorActionPreference='Stop'; " +
                "$profiles = Get-NetConnectionProfile | Where-Object { $_.InterfaceAlias -like 'vnt-tun*' }; " +
                "if (-not $profiles) { Write-Output 'NONE'; exit 0 }; " +
                "foreach ($p in $profiles) { " +
                "  Set-NetConnectionProfile -InterfaceAlias $p.InterfaceAlias -NetworkCategory CATEGORY -ErrorAction Stop " +
                "}; " +
                "Write-Output ('OK ' + ($profiles | Measure-Object).Count)";
            script = script.Replace("CATEGORY", category);

            try
            {
                var startInfo = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"" + script + "\"",
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };
                using (var ps = Process.Start(startInfo))
                {
                    string output = ps.StandardOutput.ReadToEnd().Trim();
                    string error = ps.StandardError.ReadToEnd().Trim();
                    ps.WaitForExit(15000);
                    if (output.StartsWith("NONE"))
                    {
                        message = "尚未创建虚拟网卡";
                        return false;
                    }
                    if (output.StartsWith("OK"))
                    {
                        message = "已设置为" + (category == "Private" ? "专用" : "公用");
                        return true;
                    }
                    message = error.Length > 0 ? error : output;
                    return false;
                }
            }
            catch (Exception error)
            {
                message = error.Message;
                return false;
            }
        }
        /// <summary>执行一次性查询命令并返回输出。</summary>
        private string RunQuery(string arguments, int timeoutMs)
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = exePath,
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
                WorkingDirectory = Path.GetDirectoryName(exePath) ?? Environment.CurrentDirectory
            };
            using (var query = Process.Start(startInfo))
            {
                var stdout = query.StandardOutput.ReadToEnd();
                var stderr = query.StandardError.ReadToEnd();
                if (!query.WaitForExit(timeoutMs))
                {
                    try { query.Kill(); } catch (Exception) { }
                    throw new TimeoutException("查询超时");
                }
                return stdout + stderr;
            }
        }
    }
}
