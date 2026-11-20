package main

import (
	"errors"
	"os"

	"github.com/lxn/walk"
	"golang.org/x/sys/windows/registry"
)

const (
	runKeyPath = `Software\Microsoft\Windows\CurrentVersion\Run`
	runValue   = "VntGoClient"
)

// IsAutoStartEnabled 判断开机自启是否已写入注册表。
func IsAutoStartEnabled() bool {
	key, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer key.Close()
	_, _, err = key.GetStringValue(runValue)
	return err == nil
}

// SetAutoStart 写入或移除开机自启项。
func SetAutoStart(enabled bool) error {
	key, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE)
	if err != nil {
		return errors.New("无法写入注册表，请以管理员身份运行")
	}
	defer key.Close()

	if !enabled {
		if err := key.DeleteValue(runValue); err != nil && !errors.Is(err, registry.ErrNotExist) {
			return err
		}
		return nil
	}

	exe, err := os.Executable()
	if err != nil {
		return err
	}
	// 开机启动时直接进入托盘，不打断用户。
	command := `"` + exe + `" --minimized`
	return key.SetStringValue(runValue, command)
}

// applyAutoStart 根据配置同步注册表，失败时仅提示不阻断操作。
func (a *App) applyAutoStart() {
	if err := SetAutoStart(a.cfg.AutoStart); err != nil {
		walk.MsgBox(a.mw, "提示", "设置开机启动失败："+err.Error(), walk.MsgBoxIconWarning)
	}
}
