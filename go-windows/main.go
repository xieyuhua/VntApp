package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/lxn/walk"
	. "github.com/lxn/walk/declarative"
)

// 界面控件。
type App struct {
	mw *walk.MainWindow
	ni *walk.NotifyIcon

	// 状态页
	lblStatus   *walk.Label
	lblIP       *walk.Label
	lblNat      *walk.Label
	lblServer   *walk.Label
	lblPublicIP *walk.Label
	peers       *walk.TableView
	peerModel   *PeerModel

	// 配置页
	edToken    *walk.LineEdit
	edServer   *walk.LineEdit
	edDevice   *walk.LineEdit
	edPassword *walk.LineEdit
	cbCipher   *walk.ComboBox
	edMtu      *walk.LineEdit
	edVirtIP   *walk.LineEdit
	cbCategory *walk.ComboBox
	edStun     *walk.LineEdit
	edInRoutes *walk.LineEdit
	edOutRoutes *walk.LineEdit
	chkFinger  *walk.CheckBox
	chkCompress *walk.CheckBox
	chkAutoStart *walk.CheckBox

	// 日志页
	edLog *walk.TextEdit

	btnStart, btnStop, btnRefresh *walk.PushButton

	cfg      *Config
	vnt      *Vnt
	stopCh   chan struct{}
	exiting  bool
}

// PeerModel 提供表格数据。
type PeerModel struct {
	walk.TableModelBase
	items []Peer
}

func (m *PeerModel) RowCount() int { return len(m.items) }

func (m *PeerModel) Value(row, col int) interface{} {
	if row < 0 || row >= len(m.items) {
		return ""
	}
	p := m.items[row]
	switch col {
	case 0:
		return p.Name
	case 1:
		return p.Ip
	case 2:
		return p.Status
	case 3:
		return TranslatePath(p.Path)
	case 4:
		return p.Rtt
	}
	return ""
}

func TranslatePath(path string) string {
	switch path {
	case "p2p":
		return "P2P 直连"
	case "client-relay":
		return "节点中继"
	case "server-relay":
		return "服务器中继"
	case "":
		return "-"
	default:
		return path
	}
}

func main() {
	app := &App{cfg: LoadConfig()}
	app.vnt = NewVnt(filepath.Join(ExeDir(), "vnt-cli.exe"), ExeDir())
	app.vnt.SetLogHandler(func(line string) { app.appendLog(line) })

	if err := app.build(); err != nil {
		walk.MsgBox(nil, "启动失败", err.Error(), walk.MsgBoxIconError)
		return
	}

	app.applyConfigToUi()
	app.buildTray()
	app.hookCloseToTray()

	// 每 3 秒刷新运行状态。walk 没有内置定时器，用 ticker + Synchronize。
	app.stopCh = make(chan struct{})
	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				app.mw.Synchronize(func() { app.refresh() })
			case <-app.stopCh:
				return
			}
		}
	}()

	// 开机自启时最小化到托盘并自动连接。
	minimized := hasArg("--minimized")
	if minimized {
		app.mw.Hide()
		if app.cfg.AutoStart && app.cfg.Token != "" {
			app.startVnt()
		}
	}

	app.mw.Run()
}

func hasArg(flag string) bool {
	for _, a := range os.Args {
		if a == flag {
			return true
		}
	}
	return false
}

func (a *App) build() error {
	a.peerModel = &PeerModel{}

	return MainWindow{
		AssignTo: &a.mw,
		Title:    "VNT 虚拟组网",
		MinSize:  Size{Width: 880, Height: 640},
		Size:     Size{Width: 980, Height: 700},
		Layout:   VBox{},
		Children: []Widget{
			TabWidget{
				Pages: []TabPage{
					a.buildStatusPage(),
					a.buildConfigPage(),
					a.buildLogPage(),
				},
			},
		},
	}.Create()
}

func (a *App) buildStatusPage() TabPage {
	return TabPage{
		Title:  "状态",
		Layout: VBox{Margins: Margins{Left: 12, Top: 10, Right: 12, Bottom: 10}},
		Children: []Widget{
			// 顶部状态区：左侧信息，右侧操作按钮。
			Composite{
				Layout: HBox{},
				Children: []Widget{
					Composite{
						Layout: VBox{MarginsZero: true},
						Children: []Widget{
							Label{
								AssignTo:  &a.lblStatus,
								Text:      "未运行",
								Font:      Font{Family: "Microsoft YaHei UI", PointSize: 16, Bold: true},
								TextColor: walk.RGB(128, 128, 128),
							},
							Label{AssignTo: &a.lblIP, Text: "虚拟 IP：-"},
							Label{AssignTo: &a.lblNat, Text: "NAT 类型：-"},
							Label{AssignTo: &a.lblServer, Text: "服务器：-"},
							Label{AssignTo: &a.lblPublicIP, Text: "公网 IP：-"},
						},
					},
					HSpacer{},
					Composite{
						Layout: VBox{MarginsZero: true},
						Children: []Widget{
							PushButton{
								AssignTo:  &a.btnStart,
								Text:      "启动组网",
								MinSize:   Size{Width: 110},
								OnClicked: func() { a.startVnt() },
							},
							PushButton{
								AssignTo:  &a.btnStop,
								Text:      "停止",
								MinSize:   Size{Width: 110},
								Enabled:   false,
								OnClicked: func() { a.stopVnt() },
							},
							PushButton{
								AssignTo:  &a.btnRefresh,
								Text:      "刷新",
								MinSize:   Size{Width: 110},
								OnClicked: func() { a.refresh() },
							},
						},
					},
				},
			},
			VSeparator{},
			Label{
				Text: "在线设备",
				Font: Font{Family: "Microsoft YaHei UI", PointSize: 10, Bold: true},
			},
			TableView{
				AssignTo:         &a.peers,
				AlternatingRowBG: true,
				ColumnsOrderable: false,
				Columns: []TableViewColumn{
					{Title: "设备名称", Width: 220},
					{Title: "虚拟 IP", Width: 160},
					{Title: "状态", Width: 100},
					{Title: "连接方式", Width: 160},
					{Title: "延迟(ms)", Width: 100},
				},
				Model: a.peerModel,
			},
		},
	}
}

func (a *App) buildConfigPage() TabPage {
	return TabPage{
		Title:  "配置",
		Layout: VBox{Margins: Margins{Left: 12, Top: 10, Right: 12, Bottom: 10}},
		Children: []Widget{
			ScrollView{
				Layout: VBox{},
				Children: []Widget{
					Composite{
						Layout: Grid{Columns: 2, Spacing: 6},
						Children: []Widget{
							Label{Text: "Token *"},
							LineEdit{AssignTo: &a.edToken, ToolTipText: "与服务端 --white-token 一致"},

							Label{Text: "服务器地址 *"},
							LineEdit{AssignTo: &a.edServer, ToolTipText: "例如 www.example.com:8004"},

							Label{Text: "设备名称"},
							LineEdit{AssignTo: &a.edDevice, ToolTipText: "其他设备看到的名字"},

							Label{Text: "加密密码"},
							LineEdit{AssignTo: &a.edPassword, PasswordMode: true, ToolTipText: "需与其他设备一致"},

							Label{Text: "加密模式"},
							ComboBox{
								AssignTo: &a.cbCipher,
								Model:    []string{"aes_gcm", "chacha20_poly1305", "chacha20", "aes_cbc", "aes_ecb", "xor"},
							},

							Label{Text: "MTU"},
							LineEdit{AssignTo: &a.edMtu, ToolTipText: "默认 1400"},

							Label{Text: "虚拟 IP"},
							LineEdit{AssignTo: &a.edVirtIP, ToolTipText: "留空由服务端分配；指定后不可与其他设备重复"},

							Label{Text: "网络类型"},
							ComboBox{
								AssignTo:     &a.cbCategory,
								Model:        []string{"专用（允许被访问）", "公用（隔离）"},
								CurrentIndex: 0,
							},

							Label{Text: "STUN 服务器"},
							LineEdit{AssignTo: &a.edStun, ToolTipText: "多个用逗号分隔；留空无法 P2P"},

							Label{Text: "入栈网段"},
							LineEdit{AssignTo: &a.edInRoutes, ToolTipText: "格式 网段,出口节点虚拟IP"},

							Label{Text: "出栈允许网段"},
							LineEdit{AssignTo: &a.edOutRoutes, ToolTipText: "允许他人访问的本机网段"},
						},
					},
					Composite{
						Layout: HBox{MarginsZero: true},
						Children: []Widget{
							CheckBox{AssignTo: &a.chkFinger, Text: "校验服务端指纹"},
							CheckBox{AssignTo: &a.chkCompress, Text: "启用 LZ4 压缩"},
							CheckBox{AssignTo: &a.chkAutoStart, Text: "开机自动启动"},
						},
					},
					Composite{
						Layout: HBox{MarginsZero: true},
						Children: []Widget{
							PushButton{
								Text:    "保存配置",
								MinSize: Size{Width: 100},
								OnClicked: func() {
									if a.saveConfig(true) {
										a.applyAutoStart()
										walk.MsgBox(a.mw, "VNT", "配置已保存", walk.MsgBoxIconInformation)
									}
								},
							},
							PushButton{
								Text:    "重新加载",
								MinSize: Size{Width: 100},
								OnClicked: func() {
									a.cfg = LoadConfig()
									a.applyConfigToUi()
								},
							},
						},
					},
				},
			},
		},
	}
}

func (a *App) buildLogPage() TabPage {
	return TabPage{
		Title:  "运行日志",
		Layout: VBox{},
		Children: []Widget{
			TextEdit{
				AssignTo: &a.edLog,
				ReadOnly: true,
				VScroll:  true,
				HScroll:  true,
			},
			Composite{
				Layout: HBox{MarginsZero: true},
				Children: []Widget{
					PushButton{
						Text: "清空日志",
						OnClicked: func() {
							a.vnt.ClearLog()
							if a.edLog != nil {
								a.edLog.SetText("")
							}
						},
					},
					PushButton{
						Text: "复制日志",
						OnClicked: func() {
							if err := walk.Clipboard().SetText(a.vnt.LogText()); err != nil {
								walk.MsgBox(a.mw, "复制失败", err.Error(), walk.MsgBoxIconWarning)
							}
						},
					},
				},
			},
		},
	}
}

// applyConfigToUi 把配置写入各输入框。
func (a *App) applyConfigToUi() {
	a.edToken.SetText(a.cfg.Token)
	a.edServer.SetText(a.cfg.Server)
	a.edDevice.SetText(a.cfg.DeviceName)
	a.edPassword.SetText(a.cfg.Password)
	a.edMtu.SetText(a.cfg.Mtu)
	a.edVirtIP.SetText(a.cfg.VirtualIp)
	a.edStun.SetText(strings.Join(a.cfg.StunServers, ", "))
	a.edInRoutes.SetText(strings.Join(a.cfg.InRoutes, ", "))
	a.edOutRoutes.SetText(strings.Join(a.cfg.OutRoutes, ", "))
	a.chkFinger.SetChecked(a.cfg.UseFinger)
	a.chkCompress.SetChecked(a.cfg.Compress)
	a.chkAutoStart.SetChecked(a.cfg.AutoStart)

	for i, v := range []string{"aes_gcm", "chacha20_poly1305", "chacha20", "aes_cbc", "aes_ecb", "xor"} {
		if v == a.cfg.CipherModel {
			a.cbCipher.SetCurrentIndex(i)
			break
		}
	}
	if a.cfg.NetworkCategory == "Public" {
		a.cbCategory.SetCurrentIndex(1)
	} else {
		a.cbCategory.SetCurrentIndex(0)
	}
}

// saveConfig 从界面读取配置并保存。
func (a *App) saveConfig(validate bool) bool {
	a.cfg.Token = strings.TrimSpace(a.edToken.Text())
	a.cfg.Server = strings.TrimSpace(a.edServer.Text())
	a.cfg.DeviceName = strings.TrimSpace(a.edDevice.Text())
	a.cfg.Password = a.edPassword.Text()
	a.cfg.Mtu = strings.TrimSpace(a.edMtu.Text())
	a.cfg.VirtualIp = strings.TrimSpace(a.edVirtIP.Text())
	a.cfg.StunServers = SplitList(a.edStun.Text())
	a.cfg.InRoutes = SplitList(a.edInRoutes.Text())
	a.cfg.OutRoutes = SplitList(a.edOutRoutes.Text())
	a.cfg.UseFinger = a.chkFinger.Checked()
	a.cfg.Compress = a.chkCompress.Checked()
	a.cfg.AutoStart = a.chkAutoStart.Checked()

	if idx := a.cbCipher.CurrentIndex(); idx >= 0 {
		a.cfg.CipherModel = a.cbCipher.Model().([]string)[idx]
	}
	if a.cbCategory.CurrentIndex() == 1 {
		a.cfg.NetworkCategory = "Public"
	} else {
		a.cfg.NetworkCategory = "Private"
	}

	if validate {
		if a.cfg.Token == "" {
			walk.MsgBox(a.mw, "配置无效", "Token 不能为空", walk.MsgBoxIconWarning)
			return false
		}
		if a.cfg.Server == "" {
			walk.MsgBox(a.mw, "配置无效", "服务器地址不能为空", walk.MsgBoxIconWarning)
			return false
		}
		if a.cfg.VirtualIp != "" && !IsValidIpv4(a.cfg.VirtualIp) {
			walk.MsgBox(a.mw, "配置无效",
				"虚拟 IP 格式无效，应为 IPv4 地址，例如 192.167.1.50", walk.MsgBoxIconWarning)
			return false
		}
	}

	if err := a.cfg.Save(); err != nil {
		if validate {
			walk.MsgBox(a.mw, "保存失败", err.Error(), walk.MsgBoxIconError)
		}
		return false
	}
	return true
}

// IsValidIpv4 校验点分十进制格式。
func IsValidIpv4(text string) bool {
	parts := strings.Split(text, ".")
	if len(parts) != 4 {
		return false
	}
	for _, p := range parts {
		if len(p) == 0 || len(p) > 3 {
			return false
		}
		n := 0
		for _, c := range p {
			if c < '0' || c > '9' {
				return false
			}
			n = n*10 + int(c-'0')
		}
		if n > 255 {
			return false
		}
	}
	return true
}

func (a *App) startVnt() {
	if !a.saveConfig(true) {
		return
	}
	if err := a.vnt.Start(a.cfg); err != nil {
		walk.MsgBox(a.mw, "启动失败", err.Error(), walk.MsgBoxIconError)
		return
	}
	a.lblStatus.SetText("正在连接…")
	a.lblStatus.SetTextColor(walk.RGB(217, 119, 6))
	a.btnStart.SetEnabled(false)
	a.btnStop.SetEnabled(true)

	// 网卡由核心创建，等它就绪后再改网络类别。
	go func() {
		time.Sleep(6 * time.Second)
		category := a.cfg.NetworkCategory
		msg, err := SetNetworkCategory(category)
		if err != nil {
			a.appendLog("网络类型设置失败：" + err.Error())
		} else {
			a.appendLog("网络类型：" + msg)
		}
	}()
}

func (a *App) stopVnt() {
	a.vnt.Stop()
	a.lblStatus.SetText("已停止")
	a.lblStatus.SetTextColor(walk.RGB(128, 128, 128))
	a.lblIP.SetText("虚拟 IP：-")
	a.lblNat.SetText("NAT 类型：-")
	a.lblServer.SetText("服务器：-")
	a.lblPublicIP.SetText("公网 IP：-")
	a.peerModel.items = nil
	a.peerModel.PublishRowsReset()
	a.btnStart.SetEnabled(true)
	a.btnStop.SetEnabled(false)
}

// refresh 拉取最新状态并刷新界面。
func (a *App) refresh() {
	if !a.vnt.IsRunning() {
		return
	}
	info, err := a.vnt.QueryLocalInfo()
	if err != nil {
		return
	}
	if info.ConnStatus == "Connected" {
		a.lblStatus.SetText("● 已连接")
		a.lblStatus.SetTextColor(walk.RGB(22, 163, 74))
	} else {
		a.lblStatus.SetText("● 连接中")
		a.lblStatus.SetTextColor(walk.RGB(217, 119, 6))
	}
	shown := func(v string) string {
		if v == "" {
			return "-"
		}
		return v
	}
	a.lblIP.SetText("虚拟 IP：" + shown(info.VirtualIp))
	a.lblNat.SetText("NAT 类型：" + shown(info.NatType))
	a.lblServer.SetText("服务器：" + shown(info.RelayServer))
	a.lblPublicIP.SetText("公网 IP：" + shown(info.PublicIps))

	peers, err := a.vnt.QueryPeers()
	if err != nil {
		return
	}
	a.peerModel.items = peers
	a.peerModel.PublishRowsReset()

	online, direct := 0, 0
	for _, p := range peers {
		if p.Status == "Online" {
			online++
			if p.Path == "p2p" {
				direct++
			}
		}
	}
	a.updateTrayText(online, direct)
}

func (a *App) appendLog(line string) {
	// 日志可能来自核心线程，必须切回 UI 线程更新控件。
	a.mw.Synchronize(func() {
		if a.edLog == nil {
			return
		}
		text := a.edLog.Text()
		text += line + "\r\n"
		if len(text) > 400_000 {
			text = text[len(text)-200_000:]
		}
		a.edLog.SetText(text)
	})
}

// ---- 托盘 ----

func (a *App) buildTray() {
	icon, _ := walk.NewIconFromFile(filepath.Join(ExeDir(), "vnt.ico"))
	ni, err := walk.NewNotifyIcon(a.mw)
	if err != nil {
		return
	}
	a.ni = ni
	if icon != nil {
		_ = ni.SetIcon(icon)
	}
	ni.SetToolTip("VNT 虚拟组网")

	ni.MouseDown().Attach(func(x, y int, button walk.MouseButton) {
		if button == walk.LeftButton {
			a.restoreFromTray()
		}
	})

	show := walk.NewAction()
	_ = show.SetText("显示主窗口")
	show.SetDefault(true)
	show.Triggered().Attach(func() { a.restoreFromTray() })
	ni.ContextMenu().Actions().Add(show)
	ni.ContextMenu().Actions().Add(walk.NewSeparatorAction())

	start := walk.NewAction()
	_ = start.SetText("启动组网")
	start.Triggered().Attach(func() {
		if !a.vnt.IsRunning() {
			a.startVnt()
		}
	})
	ni.ContextMenu().Actions().Add(start)

	stop := walk.NewAction()
	_ = stop.SetText("停止组网")
	stop.Triggered().Attach(func() {
		if a.vnt.IsRunning() {
			a.stopVnt()
		}
	})
	ni.ContextMenu().Actions().Add(stop)
	ni.ContextMenu().Actions().Add(walk.NewSeparatorAction())

	exit := walk.NewAction()
	_ = exit.SetText("退出")
	exit.Triggered().Attach(func() { a.exitApplication() })
	ni.ContextMenu().Actions().Add(exit)

	_ = ni.SetVisible(true)
}

func (a *App) updateTrayText(online, direct int) {
	if a.ni == nil {
		return
	}
	text := fmt.Sprintf("VNT | 在线 %d | 直连 %d", online, direct)
	if len(text) > 63 {
		text = text[:63]
	}
	_ = a.ni.SetToolTip(text)
}

func (a *App) restoreFromTray() {
	a.mw.Show()
	a.mw.Activate()
}

func (a *App) exitApplication() {
	a.exiting = true
	if a.vnt.IsRunning() {
		if walk.MsgBox(a.mw, "VNT", "组网正在运行，退出将断开连接。确定退出吗？",
			walk.MsgBoxYesNo|walk.MsgBoxIconQuestion) != 6 { // 6 = IDYES
			a.exiting = false
			return
		}
		a.vnt.Stop()
	}
	if a.stopCh != nil {
		close(a.stopCh)
		a.stopCh = nil
	}
	if a.ni != nil {
		_ = a.ni.SetVisible(false)
		a.ni.Dispose()
		a.ni = nil
	}
	a.mw.Close()
}

// ---- 窗口关闭 ----

// hookCloseToTray 让关闭按钮只隐藏窗口，退出走托盘菜单。
func (a *App) hookCloseToTray() {
	if a.mw == nil {
		return
	}
	a.mw.Closing().Attach(func(canceled *bool, reason walk.CloseReason) {
		if a.exiting {
			return
		}
		*canceled = true
		a.mw.Hide()
		if a.ni != nil {
			_ = a.ni.ShowCustom("VNT 仍在运行", "已最小化到托盘，组网保持连接。", nil)
		}
	})
}
