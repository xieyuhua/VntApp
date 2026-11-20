using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace VntClient
{
    /// <summary>
    /// 本地保存的组网配置。字段与 vnt-cli 的命令行参数一一对应。
    /// 存在 %APPDATA%\VntClient\config.ini，纯键值对，便于手工查看。
    /// </summary>
    public class VntConfig
    {
        public string Token = "";
        public string Server = "";
        public string DeviceName = Environment.MachineName;
        public string Password = "";
        public string CipherModel = "aes_gcm";
        public string Mtu = "1400";
        /// <summary>自定义虚拟 IP。留空则由服务端按 device_id 分配。</summary>
        public string VirtualIp = "";
        public List<string> StunServers = new List<string>();
        public List<string> InRoutes = new List<string>();
        public List<string> OutRoutes = new List<string>();
        public bool UseFinger = false;
        public bool Compress = false;
        public bool AutoStart = false;
        /// <summary>虚拟网卡的网络类别：Private（专用）或 Public（公用）。</summary>
        public string NetworkCategory = "Private";

        public static string ConfigDir
        {
            get
            {
                string dir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "VntClient");
                Directory.CreateDirectory(dir);
                return dir;
            }
        }

        public static string ConfigPath
        {
            get { return Path.Combine(ConfigDir, "config.ini"); }
        }

        public static VntConfig Load()
        {
            var config = new VntConfig();
            if (!File.Exists(ConfigPath)) return config;
            try
            {
                foreach (string raw in File.ReadAllLines(ConfigPath, Encoding.UTF8))
                {
                    string line = raw.Trim();
                    if (line.Length == 0 || line.StartsWith("#")) continue;
                    int split = line.IndexOf('=');
                    if (split <= 0) continue;
                    string key = line.Substring(0, split).Trim();
                    string value = line.Substring(split + 1).Trim();
                    switch (key)
                    {
                        case "token": config.Token = value; break;
                        case "server": config.Server = value; break;
                        case "device_name": config.DeviceName = value; break;
                        case "password": config.Password = value; break;
                        case "cipher_model": config.CipherModel = value; break;
                        case "mtu": config.Mtu = value; break;
                        case "virtual_ip": config.VirtualIp = value; break;
                        case "stun": config.StunServers = SplitList(value); break;
                        case "in_routes": config.InRoutes = SplitList(value); break;
                        case "out_routes": config.OutRoutes = SplitList(value); break;
                        case "finger": config.UseFinger = value == "1"; break;
                        case "compress": config.Compress = value == "1"; break;
                        case "auto_start": config.AutoStart = value == "1"; break;
                        case "network_category": config.NetworkCategory = value; break;
                    }
                }
            }
            catch (Exception)
            {
                // 配置损坏时退回默认值，不让程序启动失败。
            }
            return config;
        }

        public void Save()
        {
            var sb = new StringBuilder();
            sb.AppendLine("# VNT Windows 客户端配置");
            sb.AppendLine("token=" + Token);
            sb.AppendLine("server=" + Server);
            sb.AppendLine("device_name=" + DeviceName);
            sb.AppendLine("password=" + Password);
            sb.AppendLine("cipher_model=" + CipherModel);
            sb.AppendLine("mtu=" + Mtu);
            sb.AppendLine("virtual_ip=" + VirtualIp);
            sb.AppendLine("stun=" + string.Join(",", StunServers));
            sb.AppendLine("in_routes=" + string.Join(",", InRoutes));
            sb.AppendLine("out_routes=" + string.Join(",", OutRoutes));
            sb.AppendLine("finger=" + (UseFinger ? "1" : "0"));
            sb.AppendLine("compress=" + (Compress ? "1" : "0"));
            sb.AppendLine("auto_start=" + (AutoStart ? "1" : "0"));
            sb.AppendLine("network_category=" + NetworkCategory);
            File.WriteAllText(ConfigPath, sb.ToString(), new UTF8Encoding(false));
        }

        /// <summary>把配置翻译成 vnt-cli 的命令行参数。</summary>
        public string ToArguments()
        {
            var args = new List<string>();
            args.Add("-k " + Quote(Token));
            args.Add("-s " + Quote(Server));
            args.Add("-n " + Quote(DeviceName));
            if (!string.IsNullOrEmpty(Password)) args.Add("-w " + Quote(Password));
            if (!string.IsNullOrEmpty(CipherModel)) args.Add("--model " + CipherModel);
            if (!string.IsNullOrEmpty(Mtu)) args.Add("-u " + Mtu);
            if (!string.IsNullOrWhiteSpace(VirtualIp)) args.Add("--ip " + Quote(VirtualIp.Trim()));
            foreach (string stun in StunServers)
            {
                if (!string.IsNullOrWhiteSpace(stun)) args.Add("-e " + Quote(stun.Trim()));
            }
            foreach (string route in InRoutes)
            {
                if (!string.IsNullOrWhiteSpace(route)) args.Add("-i " + Quote(route.Trim()));
            }
            foreach (string route in OutRoutes)
            {
                if (!string.IsNullOrWhiteSpace(route)) args.Add("-o " + Quote(route.Trim()));
            }
            if (UseFinger) args.Add("--finger");
            if (Compress) args.Add("--compressor lz4");
            return string.Join(" ", args);
        }

        private static string Quote(string value)
        {
            // 参数里可能出现空格或逗号，统一加引号避免被拆开。
            return "\"" + (value ?? "").Replace("\"", "") + "\"";
        }

        private static List<string> SplitList(string value)
        {
            return value.Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries)
                .Select(v => v.Trim())
                .Where(v => v.Length > 0)
                .ToList();
        }
    }
}
