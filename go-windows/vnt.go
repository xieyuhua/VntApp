package main

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Peer 表示一个在线设备。
type Peer struct {
	Name   string
	Ip     string
	Status string
	Path   string
	Rtt    string
}

// LocalInfo 表示当前实例的状态。
type LocalInfo struct {
	VirtualIp    string
	NatType      string
	RelayServer  string
	PublicIps    string
	ConnStatus   string
	Name         string
	VirtualGw    string
	VirtualMask  string
}

// Vnt 托管 vnt-cli.exe 子进程。
//
// vnt-cli 是长驻进程，状态通过一次性查询命令（--info/--list）获取，
// 这些命令依赖进程写在 cwd 下的状态文件，因此工作目录必须固定。
type Vnt struct {
	exePath string
	workDir string

	mu      sync.Mutex
	cmd     *exec.Cmd
	logBuf  strings.Builder
	logMu   sync.Mutex
	onLog   func(string)
	exited  bool
}

// NewVnt 创建进程管理器。
func NewVnt(exePath, workDir string) *Vnt {
	return &Vnt{exePath: exePath, workDir: workDir}
}

// SetLogHandler 注册日志回调；回调可能在任意 goroutine 中被调用。
func (v *Vnt) SetLogHandler(fn func(string)) {
	v.mu.Lock()
	v.onLog = fn
	v.mu.Unlock()
}

// IsRunning 判断子进程是否在运行。
func (v *Vnt) IsRunning() bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.cmd != nil && !v.exited
}

// LogText 返回当前日志内容。
func (v *Vnt) LogText() string {
	v.logMu.Lock()
	defer v.logMu.Unlock()
	return v.logBuf.String()
}

// ClearLog 清空日志。
func (v *Vnt) ClearLog() {
	v.logMu.Lock()
	v.logBuf.Reset()
	v.logMu.Unlock()
}

// Start 启动组网。
func (v *Vnt) Start(cfg *Config) error {
	if v.IsRunning() {
		return errors.New("组网已在运行")
	}

	args := cfg.ToArguments()
	cmd := exec.Command(v.exePath, args...)
	cmd.Dir = v.workDir
	// 隐藏控制台窗口，否则每次启动都会闪一个黑框。
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return err
	}

	v.ClearLog()
	v.appendLog("=== 启动组网 ===")
	v.appendLog("参数：" + strings.Join(args, " "))

	if err := cmd.Start(); err != nil {
		return err
	}

	v.mu.Lock()
	v.cmd = cmd
	v.exited = false
	v.mu.Unlock()

	go v.pump(stdout)
	go v.pump(stderr)
	go func() {
		_ = cmd.Wait()
		v.mu.Lock()
		v.exited = true
		v.mu.Unlock()
		v.appendLog("=== 进程已退出 ===")
	}()
	return nil
}

// pump 逐行读取子进程输出并转发。
func (v *Vnt) pump(r io.Reader) {
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		v.appendLog(scanner.Text())
	}
}

func (v *Vnt) appendLog(line string) {
	if line == "" {
		return
	}
	v.logMu.Lock()
	v.logBuf.WriteString(line)
	v.logBuf.WriteString("\r\n")
	// 限制日志体积，避免长时间运行占用内存。
	if v.logBuf.Len() > 400_000 {
		s := v.logBuf.String()
		trimmed := s[len(s)-200_000:]
		v.logBuf.Reset()
		v.logBuf.WriteString(trimmed)
	}
	v.logMu.Unlock()

	v.mu.Lock()
	handler := v.onLog
	v.mu.Unlock()
	if handler != nil {
		handler(line)
	}
}

// Stop 停止组网：先发停止命令，再强制结束进程。
func (v *Vnt) Stop() {
	_, _, _ = v.runQuery("--stop", 4*time.Second)

	v.mu.Lock()
	cmd := v.cmd
	v.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
	v.appendLog("=== 组网已停止 ===")
}

// QueryLocalInfo 读取本机状态。
func (v *Vnt) QueryLocalInfo() (*LocalInfo, error) {
	out, _, err := v.runQuery("--info", 5*time.Second)
	if err != nil {
		return nil, err
	}
	info := &LocalInfo{}
	for _, raw := range strings.Split(out, "\n") {
		line := strings.TrimSpace(raw)
		key, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		switch key {
		case "Name":
			info.Name = value
		case "Virtual ip":
			info.VirtualIp = value
		case "Virtual gateway":
			info.VirtualGw = value
		case "Virtual netmask":
			info.VirtualMask = value
		case "Connection status":
			info.ConnStatus = value
		case "NAT type":
			info.NatType = value
		case "Relay server":
			info.RelayServer = value
		case "Public ips":
			info.PublicIps = value
		}
	}
	return info, nil
}

// QueryPeers 读取在线设备列表。
func (v *Vnt) QueryPeers() ([]Peer, error) {
	out, _, err := v.runQuery("--list", 5*time.Second)
	if err != nil {
		return nil, err
	}
	peers := make([]Peer, 0, 16)
	for _, raw := range strings.Split(out, "\n") {
		line := strings.TrimRight(raw, "\r")
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "Name") {
			continue
		}
		fields := strings.Fields(trimmed)
		if len(fields) < 3 {
			continue
		}
		p := Peer{Name: fields[0], Ip: fields[1], Status: fields[2]}
		if len(fields) > 3 {
			p.Path = fields[3]
		}
		if len(fields) > 4 {
			p.Rtt = fields[4]
		}
		peers = append(peers, p)
	}
	return peers, nil
}

// runQuery 在该实例的工作目录里执行一次性查询命令。
func (v *Vnt) runQuery(arguments string, timeout time.Duration) (string, string, error) {
	cmd := exec.Command(v.exePath, strings.Fields(arguments)...)
	cmd.Dir = v.workDir
	// 状态查询每 3 秒执行一次，必须隐藏窗口，否则会不断弹出控制台黑框。
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}

	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Start(); err != nil {
		return "", "", err
	}

	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	select {
	case err := <-done:
		return stdout.String(), stderr.String(), err
	case <-time.After(timeout):
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		return "", "", fmt.Errorf("查询超时")
	}
}

// SetNetworkCategory 设置虚拟网卡的网络类别。
//
// Windows 默认把新网卡归类为"公用"，会限制入站连接；改为"专用"后
// 同网段的其他设备才能主动访问本机。每次新建网卡都会生成新的连接配置文件，
// 因此按 InterfaceAlias 通配匹配并批量处理。
func SetNetworkCategory(category string) (string, error) {
	if category != "Private" && category != "Public" {
		return "", errors.New("无效的网络类别")
	}
	script := fmt.Sprintf(
		"$ErrorActionPreference='Stop'; "+
			"$p = Get-NetConnectionProfile | Where-Object { $_.InterfaceAlias -like 'vnt-tun*' }; "+
			"if (-not $p) { Write-Output 'NONE'; exit 0 }; "+
			"foreach ($x in $p) { Set-NetConnectionProfile -InterfaceAlias $x.InterfaceAlias "+
			"-NetworkCategory %s -ErrorAction Stop }; "+
			"Write-Output ('OK ' + ($p | Measure-Object).Count)", category)

	out, errOut, err := runPowerShell(script)
	if err != nil {
		if errOut != "" {
			return "", errors.New(errOut)
		}
		return "", err
	}
	out = strings.TrimSpace(out)
	if strings.HasPrefix(out, "NONE") {
		return "", errors.New("尚未创建虚拟网卡")
	}
	if strings.HasPrefix(out, "OK") {
		if category == "Private" {
			return "已设置为专用", nil
		}
		return "已设置为公用", nil
	}
	return "", errors.New(out)
}

func runPowerShell(script string) (string, string, error) {
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive",
		"-ExecutionPolicy", "Bypass", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}

	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Start(); err != nil {
		return "", "", err
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		return stdout.String(), strings.TrimSpace(stderr.String()), err
	case <-time.After(15 * time.Second):
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		return "", "", errors.New("PowerShell 超时")
	}
}

// ParseNetmaskPrefix 把点分十进制掩码转成前缀长度。
func ParseNetmaskPrefix(mask string) int {
	parts := strings.Split(mask, ".")
	if len(parts) != 4 {
		return 24
	}
	var value uint32
	for _, p := range parts {
		n, err := strconv.Atoi(strings.TrimSpace(p))
		if err != nil || n < 0 || n > 255 {
			return 24
		}
		value = value<<8 | uint32(n)
	}
	count := 0
	for value != 0 {
		count += int(value & 1)
		value >>= 1
	}
	return count
}

// ExeDir 返回程序所在目录。
func ExeDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(exe)
}
