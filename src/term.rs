//! 终端交互抽象:TTY 走 dialoguer(密码隐藏回显),管道/CI 走逐行读取。
//! E2E 测试与脚本化依赖管道路径。

use std::io::{BufRead, IsTerminal, Write};

pub trait Prompter {
    fn select(&self, prompt: &str, items: &[&str]) -> std::io::Result<usize>;
    fn input(&self, prompt: &str, default: Option<&str>) -> std::io::Result<String>;
    /// 密码/敏感输入。TTY 下隐藏回显;管道下可见(打印警告)。
    fn hidden(&self, prompt: &str) -> std::io::Result<String>;
}

pub fn detect_prompter() -> Box<dyn Prompter> {
    if std::io::stdin().is_terminal() {
        Box::new(TtyPrompter)
    } else {
        Box::new(PipePrompter)
    }
}

struct TtyPrompter;

impl Prompter for TtyPrompter {
    fn select(&self, prompt: &str, items: &[&str]) -> std::io::Result<usize> {
        dialoguer::Select::new()
            .with_prompt(prompt)
            .items(items)
            .default(0)
            .interact()
            .map_err(ioify)
    }

    fn input(&self, prompt: &str, default: Option<&str>) -> std::io::Result<String> {
        let mut input = dialoguer::Input::<String>::new().with_prompt(prompt);
        if let Some(d) = default {
            input = input.default(d.to_string());
        }
        input.interact_text().map_err(ioify)
    }

    fn hidden(&self, prompt: &str) -> std::io::Result<String> {
        dialoguer::Password::new()
            .with_prompt(prompt)
            .interact()
            .map_err(ioify)
    }
}

fn ioify(e: dialoguer::Error) -> std::io::Error {
    std::io::Error::other(e.to_string())
}

struct PipePrompter;

impl PipePrompter {
    fn read_line(&self, prompt: &str) -> std::io::Result<String> {
        let mut stderr = std::io::stderr();
        writeln!(stderr, "{prompt}")?;
        let mut line = String::new();
        let n = std::io::stdin().lock().read_line(&mut line)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "stdin closed",
            ));
        }
        Ok(line.trim_end_matches(['\r', '\n']).to_string())
    }
}

impl Prompter for PipePrompter {
    fn select(&self, prompt: &str, items: &[&str]) -> std::io::Result<usize> {
        loop {
            let mut stderr = std::io::stderr();
            writeln!(stderr, "{prompt}")?;
            for (i, item) in items.iter().enumerate() {
                writeln!(stderr, "  [{}] {item}", i + 1)?;
            }
            let line = self.read_line("Enter a number")?;
            if line.trim().is_empty() {
                return Ok(0);
            }
            if let Ok(n) = line.trim().parse::<usize>() {
                if (1..=items.len()).contains(&n) {
                    return Ok(n - 1);
                }
            }
            writeln!(stderr, "[warn] invalid choice, try again")?;
        }
    }

    fn input(&self, prompt: &str, default: Option<&str>) -> std::io::Result<String> {
        let hint = match default {
            Some(d) if !d.is_empty() => format!("{prompt} [{d}]"),
            _ => prompt.to_string(),
        };
        let line = self.read_line(&hint)?;
        if line.trim().is_empty() {
            if let Some(d) = default {
                return Ok(d.to_string());
            }
        }
        Ok(line.trim().to_string())
    }

    fn hidden(&self, prompt: &str) -> std::io::Result<String> {
        let mut stderr = std::io::stderr();
        writeln!(
            stderr,
            "[warn] stdin is not a terminal; the value you type will be visible"
        )?;
        self.read_line(prompt)
    }
}
