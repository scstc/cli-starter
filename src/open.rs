//! 用系统默认程序打开 URL 或文件(浏览器/图片查看器)。
//! CLI_STARTER_NO_BROWSER=1 时跳过(测试/无界面环境),打开失败仅提示不阻断。

pub fn open_with_default_app(target: &str) {
    if std::env::var_os("CLI_STARTER_NO_BROWSER").is_some() {
        return;
    }
    if let Err(e) = platform_open(target) {
        eprintln!("[warn] could not open `{target}` ({e}); open it manually");
    }
}

#[cfg(target_os = "windows")]
fn platform_open(target: &str) -> std::io::Result<()> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let status = std::process::Command::new("cmd")
        .args(["/c", "start", "", target])
        .creation_flags(CREATE_NO_WINDOW)
        .status()?;
    if status.success() {
        Ok(())
    } else {
        Err(std::io::Error::other("`start` exited non-zero"))
    }
}

#[cfg(target_os = "macos")]
fn platform_open(target: &str) -> std::io::Result<()> {
    let status = std::process::Command::new("open").arg(target).status()?;
    if status.success() {
        Ok(())
    } else {
        Err(std::io::Error::other("`open` exited non-zero"))
    }
}

#[cfg(all(unix, not(target_os = "macos")))]
fn platform_open(target: &str) -> std::io::Result<()> {
    let status = std::process::Command::new("xdg-open")
        .arg(target)
        .status()?;
    if status.success() {
        Ok(())
    } else {
        Err(std::io::Error::other("`xdg-open` exited non-zero"))
    }
}

#[cfg(not(any(windows, unix)))]
fn platform_open(_target: &str) -> std::io::Result<()> {
    Err(std::io::Error::other(
        "no default-application launcher on this platform",
    ))
}
