using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows.Forms;
using Microsoft.Win32;

namespace VntClient
{
    public class MainForm : Form
    {
        private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string RunValueName = "VntClient";

        private readonly VntConfig config;
        private readonly VntProcess vnt;
        private readonly Timer refreshTimer;

        // 配置控件
        private TextBox txtToken, txtServer, txtDeviceName, txtPassword, txtMtu, txtVirtualIp;
        private ComboBox cmbCipher;
        private TextBox txtStun, txtInRoutes, txtOutRoutes;
        private CheckBox chkFinger, chkCompress, chkAutoStart;
        private ComboBox cmbNetworkCategory;

        // 状态控件
        private Label lblStatus, lblIp, lblNat, lblServer, lblPublicIp;
        private DataGridView gridPeers;
        private TextBox txtLog;
        private Button btnStart, btnStop, btnSave, btnRefresh;
        private readonly ToolTip tooltip = new ToolTip();
        private NotifyIcon trayIcon;
        private bool exiting;

        public MainForm()
        {
            config = VntConfig.Load();
            string exePath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "vnt-cli.exe");
            vnt = new VntProcess(exePath);
            vnt.LogReceived += line => AppendLog(line);

            BuildUi();
            LoadConfigIntoUi();

            refreshTimer = new Timer { Interval = 3000 };
            refreshTimer.Tick += (s, e) => RefreshStatus();
            refreshTimer.Start();

            BuildTrayIcon();

            // 开机自启时只留在托盘，不弹出主窗口。
            if (Environment.GetCommandLineArgs().Any(a => a == "--minimized"))
            {
                WindowState = FormWindowState.Minimized;
                ShowInTaskbar = false;
                Hide();
                if (config.AutoStart && !string.IsNullOrEmpty(config.Token)) StartVnt();
            }
        }

        /// <summary>
        /// 创建托盘图标与右键菜单。
        ///
        /// <p>关闭主窗口时程序继续在后台运行，只能通过托盘菜单退出，
        /// 避免用户误关窗口导致组网断开。
        /// </summary>
        private void BuildTrayIcon()
        {
            var menu = new ContextMenuStrip();
            var showItem = new ToolStripMenuItem("显示主窗口");
            showItem.Click += (s, e) => RestoreFromTray();
            showItem.Font = new Font(showItem.Font, FontStyle.Bold);
            var startItem = new ToolStripMenuItem("启动组网");
            startItem.Click += (s, e) => { if (!vnt.IsRunning) StartVnt(); };
            var stopItem = new ToolStripMenuItem("停止组网");
            stopItem.Click += (s, e) => { if (vnt.IsRunning) StopVnt(); };
            var exitItem = new ToolStripMenuItem("退出");
            exitItem.Click += (s, e) => ExitApplication();

            menu.Items.Add(showItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(startItem);
            menu.Items.Add(stopItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(exitItem);
            menu.Opening += (s, e) =>
            {
                bool running = vnt.IsRunning;
                startItem.Enabled = !running;
                stopItem.Enabled = running;
            };

            trayIcon = new NotifyIcon
            {
                Icon = LoadAppIcon(),
                Text = "VNT 虚拟组网",
                Visible = true,
                ContextMenuStrip = menu
            };
            // 单击托盘图标显示主窗口。
            trayIcon.MouseClick += (s, e) =>
            {
                if (e.Button == MouseButtons.Left) RestoreFromTray();
            };
        }

        private static Icon LoadAppIcon()
        {
            try
            {
                string path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "vnt.ico");
                if (File.Exists(path)) return new Icon(path);
            }
            catch (Exception) { }
            // 图标缺失时退回系统默认图标，不影响功能。
            return SystemIcons.Application;
        }

        private void RestoreFromTray()
        {
            Show();
            ShowInTaskbar = true;
            WindowState = FormWindowState.Normal;
            Activate();
            BringToFront();
        }

        /// <summary>真正退出程序：停止组网并释放托盘图标。</summary>
        private void ExitApplication()
        {
            exiting = true;
            if (vnt.IsRunning)
            {
                DialogResult result = MessageBox.Show(
                    "组网正在运行，退出将断开连接。确定退出吗？", "VNT",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                if (result == DialogResult.No)
                {
                    exiting = false;
                    return;
                }
                vnt.Stop();
            }
            refreshTimer.Stop();
            if (trayIcon != null)
            {
                trayIcon.Visible = false;
                trayIcon.Dispose();
                trayIcon = null;
            }
            Application.Exit();
        }

        private void BuildUi()
        {
            Text = "VNT 虚拟组网";
            MinimumSize = new Size(880, 640);
            Size = new Size(980, 700);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Microsoft YaHei UI", 9F);

            var tabs = new TabControl { Dock = DockStyle.Fill };

            // ---- 状态页 ----
            var statusPage = new TabPage("状态");
            var statusCard = new Panel { Dock = DockStyle.Top, Height = 140, Padding = new Padding(16) };
            lblStatus = MakeLabel("未运行", 16, FontStyle.Bold, Color.Gray);
            lblStatus.Location = new Point(16, 12);
            lblIp = MakeLabel("虚拟 IP：-", 10, FontStyle.Regular, Color.Black);
            lblIp.Location = new Point(16, 46);
            lblNat = MakeLabel("NAT 类型：-", 10, FontStyle.Regular, Color.Black);
            lblNat.Location = new Point(16, 70);
            lblServer = MakeLabel("服务器：-", 10, FontStyle.Regular, Color.Black);
            lblServer.Location = new Point(16, 94);
            lblPublicIp = MakeLabel("公网 IP：-", 10, FontStyle.Regular, Color.Black);
            lblPublicIp.Location = new Point(360, 46);

            btnStart = MakeButton("启动组网", Color.FromArgb(79, 70, 229), Color.White, 120);
            btnStart.Location = new Point(700, 16);
            btnStart.Click += (s, e) => StartVnt();
            btnStop = MakeButton("停止", Color.FromArgb(220, 38, 38), Color.White, 120);
            btnStop.Location = new Point(700, 56);
            btnStop.Enabled = false;
            btnStop.Click += (s, e) => StopVnt();
            btnRefresh = MakeButton("刷新", Color.FromArgb(226, 232, 240), Color.Black, 120);
            btnRefresh.Location = new Point(700, 96);
            btnRefresh.Click += (s, e) => RefreshStatus();

            statusCard.Controls.AddRange(new Control[] {
                lblStatus, lblIp, lblNat, lblServer, lblPublicIp, btnStart, btnStop, btnRefresh
            });

            var peerHeader = new Label
            {
                Text = "在线设备",
                Dock = DockStyle.Top,
                Height = 32,
                Padding = new Padding(16, 8, 0, 0),
                Font = new Font("Microsoft YaHei UI", 10F, FontStyle.Bold)
            };

            gridPeers = new DataGridView
            {
                Dock = DockStyle.Fill,
                ReadOnly = true,
                AllowUserToAddRows = false,
                AllowUserToDeleteRows = false,
                AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
                SelectionMode = DataGridViewSelectionMode.FullRowSelect,
                RowHeadersVisible = false,
                BackgroundColor = Color.White,
                BorderStyle = BorderStyle.None
            };
            gridPeers.Columns.Add("name", "设备名称");
            gridPeers.Columns.Add("ip", "虚拟 IP");
            gridPeers.Columns.Add("status", "状态");
            gridPeers.Columns.Add("path", "连接方式");
            gridPeers.Columns.Add("rtt", "延迟(ms)");
            gridPeers.Columns["name"].FillWeight = 30;
            gridPeers.Columns["ip"].FillWeight = 20;
            gridPeers.Columns["status"].FillWeight = 12;
            gridPeers.Columns["path"].FillWeight = 24;
            gridPeers.Columns["rtt"].FillWeight = 12;

            statusPage.Controls.Add(gridPeers);
            statusPage.Controls.Add(peerHeader);
            statusPage.Controls.Add(statusCard);

            // ---- 配置页 ----
            var configPage = new TabPage("配置");
            var scroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true, Padding = new Padding(20) };
            var form = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                ColumnCount = 2,
                AutoSize = true,
                Padding = new Padding(0, 0, 0, 20)
            };
            form.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130));
            form.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

            txtToken = AddRow(form, "Token *", "");
            txtServer = AddRow(form, "服务器地址 *", "例如 www.example.com:8004");
            txtDeviceName = AddRow(form, "设备名称", "");
            txtPassword = AddRow(form, "加密密码", "留空则不加密，需与其他设备一致", true);
            cmbCipher = AddComboRow(form, "加密模式",
                new[] { "aes_gcm", "chacha20_poly1305", "chacha20", "aes_cbc", "aes_ecb", "xor" });
            txtMtu = AddRow(form, "MTU", "1400");
            txtVirtualIp = AddRow(form, "虚拟 IP", "留空由服务端分配；指定后不可与其他设备重复");
            cmbNetworkCategory = AddComboRow(form, "网络类型",
                new[] { "专用（允许被访问）", "公用（隔离）" });
            txtStun = AddRow(form, "STUN 服务器", "多个用逗号分隔；留空将无法 P2P");
            txtInRoutes = AddRow(form, "入栈网段", "格式 网段,出口节点虚拟IP；用于访问对方局域网");
            txtOutRoutes = AddRow(form, "出栈允许网段", "允许他人访问的本机网段");

            chkFinger = AddCheckRow(form, "校验服务端指纹");
            chkCompress = AddCheckRow(form, "启用 LZ4 压缩");
            chkAutoStart = AddCheckRow(form, "开机自动启动");

            var buttonRow = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 56, Padding = new Padding(0, 12, 0, 0) };
            btnSave = MakeButton("保存配置", Color.FromArgb(79, 70, 229), Color.White, 120);
            btnSave.Click += (s, e) => SaveConfig();
            var btnReset = MakeButton("重新加载", Color.FromArgb(226, 232, 240), Color.Black, 120);
            btnReset.Click += (s, e) => LoadConfigIntoUi();
            buttonRow.Controls.Add(btnSave);
            buttonRow.Controls.Add(btnReset);

            scroll.Controls.Add(buttonRow);
            scroll.Controls.Add(form);
            configPage.Controls.Add(scroll);

            // ---- 日志页 ----
            var logPage = new TabPage("运行日志");
            txtLog = new TextBox
            {
                Dock = DockStyle.Fill,
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                Font = new Font("Consolas", 9F),
                BackColor = Color.FromArgb(15, 23, 42),
                ForeColor = Color.FromArgb(226, 232, 240),
                BorderStyle = BorderStyle.None
            };
            logPage.Controls.Add(txtLog);

            tabs.TabPages.Add(statusPage);
            tabs.TabPages.Add(configPage);
            tabs.TabPages.Add(logPage);
            Controls.Add(tabs);
            // 默认进入状态页；配置页排第二，避免启动时直接暴露表单。
            tabs.SelectedTab = statusPage;
        }

        private static Label MakeLabel(string text, float size, FontStyle style, Color color)
        {
            return new Label
            {
                Text = text,
                AutoSize = true,
                Font = new Font("Microsoft YaHei UI", size, style),
                ForeColor = color
            };
        }

        private static Button MakeButton(string text, Color back, Color fore, int width)
        {
            return new Button
            {
                Text = text,
                Width = width,
                Height = 32,
                BackColor = back,
                ForeColor = fore,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Microsoft YaHei UI", 9F)
            };
        }

        private TextBox AddRow(TableLayoutPanel form, string label, string placeholder, bool password = false)
        {
            form.RowCount++;
            form.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            form.Controls.Add(new Label
            {
                Text = label,
                Anchor = AnchorStyles.Left,
                AutoSize = true,
                Margin = new Padding(0, 10, 8, 10)
            });
            var box = new TextBox
            {
                Dock = DockStyle.Fill,
                Margin = new Padding(0, 8, 0, 8),
                UseSystemPasswordChar = password
            };
            // .NET Framework 4.x 的 TextBox 没有 PlaceholderText，
            // 用共享的 ToolTip 提示格式，避免逐个新建造成资源泄漏。
            tooltip.SetToolTip(box, placeholder);
            form.Controls.Add(box);
            return box;
        }

        private ComboBox AddComboRow(TableLayoutPanel form, string label, string[] items)
        {
            form.RowCount++;
            form.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            form.Controls.Add(new Label
            {
                Text = label,
                Anchor = AnchorStyles.Left,
                AutoSize = true,
                Margin = new Padding(0, 10, 8, 10)
            });
            var combo = new ComboBox
            {
                Dock = DockStyle.Fill,
                DropDownStyle = ComboBoxStyle.DropDownList,
                Margin = new Padding(0, 8, 0, 8)
            };
            combo.Items.AddRange(items);
            combo.SelectedIndex = 0;
            form.Controls.Add(combo);
            return combo;
        }

        private CheckBox AddCheckRow(TableLayoutPanel form, string label)
        {
            form.RowCount++;
            form.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            form.Controls.Add(new Label { Text = "", AutoSize = true });
            var box = new CheckBox { Text = label, AutoSize = true, Margin = new Padding(0, 10, 0, 10) };
            form.Controls.Add(box);
            return box;
        }

        private void LoadConfigIntoUi()
        {
            txtToken.Text = config.Token;
            txtServer.Text = config.Server;
            txtDeviceName.Text = config.DeviceName;
            txtPassword.Text = config.Password;
            txtMtu.Text = config.Mtu;
            txtVirtualIp.Text = config.VirtualIp;
            cmbNetworkCategory.SelectedIndex = config.NetworkCategory == "Public" ? 1 : 0;
            cmbCipher.SelectedItem = config.CipherModel;
            if (cmbCipher.SelectedIndex < 0) cmbCipher.SelectedIndex = 0;
            txtStun.Text = string.Join(", ", config.StunServers);
            txtInRoutes.Text = string.Join(", ", config.InRoutes);
            txtOutRoutes.Text = string.Join(", ", config.OutRoutes);
            chkFinger.Checked = config.UseFinger;
            chkCompress.Checked = config.Compress;
            chkAutoStart.Checked = config.AutoStart;
        }

        private void SaveConfig()
        {
            config.Token = txtToken.Text.Trim();
            config.Server = txtServer.Text.Trim();
            config.DeviceName = txtDeviceName.Text.Trim();
            config.Password = txtPassword.Text;
            config.Mtu = txtMtu.Text.Trim();
            config.VirtualIp = txtVirtualIp.Text.Trim();
            config.CipherModel = (string)cmbCipher.SelectedItem ?? "aes_gcm";
            config.NetworkCategory = cmbNetworkCategory.SelectedIndex == 1 ? "Public" : "Private";
            config.StunServers = SplitList(txtStun.Text);
            config.InRoutes = SplitList(txtInRoutes.Text);
            config.OutRoutes = SplitList(txtOutRoutes.Text);
            config.UseFinger = chkFinger.Checked;
            config.Compress = chkCompress.Checked;
            config.AutoStart = chkAutoStart.Checked;

            if (config.Token.Length == 0)
            {
                MessageBox.Show("Token 不能为空", "配置无效", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            if (config.VirtualIp.Length > 0 && !IsValidIpv4(config.VirtualIp))
            {
                MessageBox.Show("虚拟 IP 格式无效，应为 IPv4 地址，例如 192.167.1.50",
                    "配置无效", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            if (config.Server.Length == 0)
            {
                MessageBox.Show("服务器地址不能为空", "配置无效", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            try
            {
                config.Save();
                ApplyAutoStart(config.AutoStart);
                MessageBox.Show("配置已保存", "VNT", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception error)
            {
                MessageBox.Show("保存失败：" + error.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        /// <summary>校验 IPv4 点分十进制格式。</summary>
        private static bool IsValidIpv4(string text)
        {
            string[] parts = text.Split('.');
            if (parts.Length != 4) return false;
            foreach (string part in parts)
            {
                if (part.Length == 0 || part.Length > 3) return false;
                int value;
                if (!int.TryParse(part, out value)) return false;
                if (value < 0 || value > 255) return false;
            }
            return true;
        }

        private static List<string> SplitList(string value)
        {
            return value.Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries)
                .Select(v => v.Trim())
                .Where(v => v.Length > 0)
                .ToList();
        }

        /// <summary>写入或移除注册表开机自启项。</summary>
        private void ApplyAutoStart(bool enabled)
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKeyPath, true))
                {
                    if (key == null) return;
                    if (enabled)
                    {
                        string exe = Application.ExecutablePath;
                        key.SetValue(RunValueName, "\"" + exe + "\" --minimized");
                    }
                    else
                    {
                        key.DeleteValue(RunValueName, false);
                    }
                }
            }
            catch (Exception error)
            {
                MessageBox.Show("设置开机启动失败：" + error.Message, "提示",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private void StartVnt()
        {
            try
            {
                SaveConfigSilently();
                if (string.IsNullOrEmpty(config.Token) || string.IsNullOrEmpty(config.Server))
                {
                    MessageBox.Show("请先在「配置」页填写 Token 和服务器地址", "VNT",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
                if (config.VirtualIp.Length > 0 && !IsValidIpv4(config.VirtualIp))
                {
                    MessageBox.Show("虚拟 IP 格式无效，应为 IPv4 地址", "VNT",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
                vnt.Start(config);
                SetRunningUi(true);
                AppendLog("=== 已启动组网 ===");
                ScheduleNetworkCategory();
            }
            catch (Exception error)
            {
                MessageBox.Show("启动失败：" + error.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        /// <summary>
        /// 网卡由 vnt-cli 创建，需要等它就绪后再改网络类别，
        /// 因此延迟数秒执行，并在后台线程里调用以免卡界面。
        /// </summary>
        private void ScheduleNetworkCategory()
        {
            string category = config.NetworkCategory;
            var timer = new Timer { Interval = 6000 };
            timer.Tick += (s, e) =>
            {
                timer.Stop();
                timer.Dispose();
                System.Threading.ThreadPool.QueueUserWorkItem(_ =>
                {
                    string message;
                    bool ok = VntProcess.SetNetworkCategory(category, out message);
                    BeginInvoke(new Action(() =>
                        AppendLog(ok
                            ? "网络类型：" + message
                            : "网络类型设置失败：" + message)));
                });
            };
            timer.Start();
        }

        private void SaveConfigSilently()
        {
            config.Token = txtToken.Text.Trim();
            config.Server = txtServer.Text.Trim();
            config.DeviceName = txtDeviceName.Text.Trim();
            config.Password = txtPassword.Text;
            config.Mtu = txtMtu.Text.Trim();
            config.VirtualIp = txtVirtualIp.Text.Trim();
            config.CipherModel = (string)cmbCipher.SelectedItem ?? "aes_gcm";
            config.NetworkCategory = cmbNetworkCategory.SelectedIndex == 1 ? "Public" : "Private";
            config.StunServers = SplitList(txtStun.Text);
            config.InRoutes = SplitList(txtInRoutes.Text);
            config.OutRoutes = SplitList(txtOutRoutes.Text);
            config.UseFinger = chkFinger.Checked;
            config.Compress = chkCompress.Checked;
            config.AutoStart = chkAutoStart.Checked;
            try { config.Save(); } catch (Exception) { }
        }

        private void StopVnt()
        {
            vnt.Stop();
            SetRunningUi(false);
            lblStatus.Text = "已停止";
            lblStatus.ForeColor = Color.Gray;
            lblIp.Text = lblNat.Text = lblServer.Text = lblPublicIp.Text = "-";
            gridPeers.Rows.Clear();
            if (trayIcon != null) trayIcon.Text = "VNT 虚拟组网 - 未连接";
            AppendLog("=== 已停止组网 ===");
        }

        private void SetRunningUi(bool running)
        {
            btnStart.Enabled = !running;
            btnStop.Enabled = running;
            if (running)
            {
                lblStatus.Text = "正在连接…";
                lblStatus.ForeColor = Color.FromArgb(217, 119, 6);
            }
        }

        private void AppendLog(string line)
        {
            if (txtLog.InvokeRequired)
            {
                txtLog.BeginInvoke(new Action(() => AppendLog(line)));
                return;
            }
            txtLog.AppendText(DateTime.Now.ToString("HH:mm:ss") + "  " + line + Environment.NewLine);
        }

        private void RefreshStatus()
        {
            if (!vnt.IsRunning)
            {
                if (btnStop.Enabled) SetRunningUi(false);
                return;
            }
            try
            {
                LocalInfo info = vnt.QueryLocalInfo();
                if (info.ConnectionStatus == "Connected")
                {
                    lblStatus.Text = "● 已连接";
                    lblStatus.ForeColor = Color.FromArgb(22, 163, 74);
                }
                lblIp.Text = "虚拟 IP：" + (info.VirtualIp.Length > 0 ? info.VirtualIp : "-");
                lblNat.Text = "NAT 类型：" + (info.NatType.Length > 0 ? info.NatType : "-");
                lblServer.Text = "服务器：" + (info.RelayServer.Length > 0 ? info.RelayServer : "-");
                lblPublicIp.Text = "公网 IP：" + (info.PublicIps.Length > 0 ? info.PublicIps : "-");

                List<PeerInfo> peers = vnt.QueryPeers();
                UpdatePeerGrid(peers);
                UpdateTrayText(info, peers);
            }
            catch (Exception)
            {
                // 启动初期接口可能还没就绪，忽略本轮刷新。
            }
        }

        /// <summary>把连接状态同步到托盘提示，悬停即可看到概况。</summary>
        private void UpdateTrayText(LocalInfo info, List<PeerInfo> peers)
        {
            if (trayIcon == null) return;
            if (info.ConnectionStatus != "Connected")
            {
                trayIcon.Text = "VNT 虚拟组网 - 未连接";
                return;
            }
            int online = 0;
            int direct = 0;
            foreach (PeerInfo peer in peers)
            {
                if (peer.Status != "Online") continue;
                online++;
                if (peer.Path == "p2p") direct++;
            }
            // NotifyIcon.Text 最多 63 个字符，超出会抛异常。
            string text = string.Format("VNT {0} | 在线 {1} | 直连 {2}",
                info.VirtualIp, online, direct);
            trayIcon.Text = text.Length > 63 ? text.Substring(0, 63) : text;
        }

        private void UpdatePeerGrid(List<PeerInfo> peers)
        {
            gridPeers.SuspendLayout();
            gridPeers.Rows.Clear();
            foreach (PeerInfo peer in peers)
            {
                int index = gridPeers.Rows.Add(peer.Name, peer.VirtualIp, peer.Status,
                    TranslatePath(peer.Path), peer.Rtt);
                DataGridViewRow row = gridPeers.Rows[index];
                if (peer.Status == "Online")
                {
                    row.Cells["status"].Style.ForeColor = Color.FromArgb(22, 163, 74);
                }
                else
                {
                    row.Cells["status"].Style.ForeColor = Color.Gray;
                }
                if (peer.Path == "p2p")
                {
                    row.Cells["path"].Style.ForeColor = Color.FromArgb(79, 70, 229);
                }
            }
            gridPeers.ResumeLayout();
        }

        private static string TranslatePath(string path)
        {
            switch (path)
            {
                case "p2p": return "P2P 直连";
                case "client-relay": return "节点中继";
                case "server-relay": return "服务器中继";
                default: return path.Length == 0 ? "-" : path;
            }
        }

        /// <summary>
        /// 点击关闭按钮时隐藏到托盘而不是退出。
        ///
        /// <p>组网进程需要常驻，直接退出会断开连接。真正的退出入口在托盘菜单。
        /// </summary>
        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!exiting)
            {
                e.Cancel = true;
                Hide();
                ShowInTaskbar = false;
                if (trayIcon != null)
                {
                    trayIcon.ShowBalloonTip(2000, "VNT 仍在运行",
                        "程序已最小化到托盘，组网保持连接。右键托盘图标可退出。",
                        ToolTipIcon.Info);
                }
                return;
            }
            base.OnFormClosing(e);
        }
    }

    public static class Program
    {
        [STAThread]
        public static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }
}
