package main

import (
	"os"
	"path/filepath"
	"strings"
)

// Config 保存组网配置，字段与 vnt-cli 命令行参数一一对应。
// 存放在 %APPDATA%\VntGoClient\config.ini，纯键值对便于手工查看。
type Config struct {
	Token           string
	Server          string
	DeviceName      string
	Password        string
	CipherModel     string
	Mtu             string
	VirtualIp       string
	StunServers     []string
	InRoutes        []string
	OutRoutes       []string
	UseFinger       bool
	Compress        bool
	AutoStart       bool
	NetworkCategory string
}

// DefaultConfig 返回带合理默认值的配置。
func DefaultConfig() *Config {
	hostname, _ := os.Hostname()
	return &Config{
		DeviceName:      hostname,
		CipherModel:     "aes_gcm",
		Mtu:             "1400",
		NetworkCategory: "Private",
		StunServers: []string{
			"stun.l.google.com:19302",
			"stun.qq.com:3478",
			"stun.miwifi.com:3478",
		},
	}
}

// ConfigDir 返回配置目录，不存在时创建。
func ConfigDir() string {
	base, err := os.UserConfigDir()
	if err != nil {
		base = "."
	}
	dir := filepath.Join(base, "VntGoClient")
	_ = os.MkdirAll(dir, 0o755)
	return dir
}

// ConfigPath 返回配置文件完整路径。
func ConfigPath() string {
	return filepath.Join(ConfigDir(), "config.ini")
}

// LoadConfig 读取配置；文件不存在或损坏时返回默认值。
func LoadConfig() *Config {
	cfg := DefaultConfig()
	data, err := os.ReadFile(ConfigPath())
	if err != nil {
		return cfg
	}
	for _, raw := range strings.Split(string(data), "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		switch key {
		case "token":
			cfg.Token = value
		case "server":
			cfg.Server = value
		case "device_name":
			cfg.DeviceName = value
		case "password":
			cfg.Password = value
		case "cipher_model":
			cfg.CipherModel = value
		case "mtu":
			cfg.Mtu = value
		case "virtual_ip":
			cfg.VirtualIp = value
		case "stun":
			cfg.StunServers = SplitList(value)
		case "in_routes":
			cfg.InRoutes = SplitList(value)
		case "out_routes":
			cfg.OutRoutes = SplitList(value)
		case "finger":
			cfg.UseFinger = value == "1"
		case "compress":
			cfg.Compress = value == "1"
		case "auto_start":
			cfg.AutoStart = value == "1"
		case "network_category":
			cfg.NetworkCategory = value
		}
	}
	if cfg.NetworkCategory == "" {
		cfg.NetworkCategory = "Private"
	}
	if cfg.CipherModel == "" {
		cfg.CipherModel = "aes_gcm"
	}
	if cfg.Mtu == "" {
		cfg.Mtu = "1400"
	}
	return cfg
}

// Save 写回配置文件。
func (c *Config) Save() error {
	var b strings.Builder
	b.WriteString("# VNT Go 客户端配置\n")
	writeKV(&b, "token", c.Token)
	writeKV(&b, "server", c.Server)
	writeKV(&b, "device_name", c.DeviceName)
	writeKV(&b, "password", c.Password)
	writeKV(&b, "cipher_model", c.CipherModel)
	writeKV(&b, "mtu", c.Mtu)
	writeKV(&b, "virtual_ip", c.VirtualIp)
	writeKV(&b, "stun", strings.Join(c.StunServers, ","))
	writeKV(&b, "in_routes", strings.Join(c.InRoutes, ","))
	writeKV(&b, "out_routes", strings.Join(c.OutRoutes, ","))
	writeKV(&b, "finger", boolFlag(c.UseFinger))
	writeKV(&b, "compress", boolFlag(c.Compress))
	writeKV(&b, "auto_start", boolFlag(c.AutoStart))
	writeKV(&b, "network_category", c.NetworkCategory)
	return os.WriteFile(ConfigPath(), []byte(b.String()), 0o644)
}

func writeKV(b *strings.Builder, key, value string) {
	b.WriteString(key)
	b.WriteString("=")
	b.WriteString(value)
	b.WriteString("\n")
}

func boolFlag(v bool) string {
	if v {
		return "1"
	}
	return "0"
}

// ToArguments 把配置翻译成 vnt-cli 的命令行参数。
func (c *Config) ToArguments() []string {
	args := []string{
		"-k", clean(c.Token),
		"-s", clean(c.Server),
		"-n", clean(c.DeviceName),
	}
	if c.Password != "" {
		args = append(args, "-w", clean(c.Password))
	}
	if c.CipherModel != "" {
		args = append(args, "--model", c.CipherModel)
	}
	if c.Mtu != "" {
		args = append(args, "-u", c.Mtu)
	}
	if v := strings.TrimSpace(c.VirtualIp); v != "" {
		args = append(args, "--ip", clean(v))
	}
	for _, stun := range c.StunServers {
		if s := strings.TrimSpace(stun); s != "" {
			args = append(args, "-e", clean(s))
		}
	}
	for _, route := range c.InRoutes {
		if r := strings.TrimSpace(route); r != "" {
			args = append(args, "-i", clean(r))
		}
	}
	for _, route := range c.OutRoutes {
		if r := strings.TrimSpace(route); r != "" {
			args = append(args, "-o", clean(r))
		}
	}
	if c.UseFinger {
		args = append(args, "--finger")
	}
	if c.Compress {
		args = append(args, "--compressor", "lz4")
	}
	return args
}

// quote 不再需要：Go 的 exec.Command 会把每个参数作为独立 argv 传给子进程，
// 手动加引号反而让 vnt-cli 收到带引号的字符串，导致 Token 不匹配、路径无效。
func clean(v string) string {
	return strings.TrimSpace(v)
}

// SplitList 按逗号/分号拆分并去掉空项。
func SplitList(value string) []string {
	fields := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == ';'
	})
	out := make([]string, 0, len(fields))
	for _, f := range fields {
		if v := strings.TrimSpace(f); v != "" {
			out = append(out, v)
		}
	}
	return out
}
