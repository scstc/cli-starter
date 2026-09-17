import type { ReactNode } from 'react';
import { CheckIcon } from './icons';

/**
 * 认证页统一外壳:左侧品牌叙事区(渐变 + 终端装饰) + 右侧登录卡区 + 页脚。
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="auth-shell">
      <section className="auth-brand">
        <div className="brand-top">
          <span className="logo-mark" aria-hidden="true">
            C
          </span>
          <span className="brand-name">cli-starter</span>
        </div>

        <h1 className="brand-title">
          统一身份
          <br />
          认证中心
        </h1>
        <p className="brand-sub">一次登录,授权所有开发者工具与内部服务</p>

        <ul className="brand-feats">
          <li>
            <CheckIcon className="feat-icon" />
            统一管理 CLI 与 Web 的账号凭证
          </li>
          <li>
            <CheckIcon className="feat-icon" />
            设备码授权,终端登录更安全
          </li>
          <li>
            <CheckIcon className="feat-icon" />
            图形验证码 + 短信验证码双重防护
          </li>
        </ul>

        <div className="term" aria-hidden="true">
          <div className="term-bar">
            <i />
            <i />
            <i />
            <span>terminal — cli-starter</span>
          </div>
          <pre>
            <span className="t-p">$</span> cli-starter auth login{'\n'}
            <span className="t-dim">{'>'} waiting for authorization ...</span>
            {'\n'}
            <span className="t-ok">[ok]</span> Logged in as <span className="t-hl">you</span>
          </pre>
        </div>

        <footer className="auth-footer">
          <span>© 2026 cli-starter</span>
          <span>隐私政策</span>
          <span>服务条款</span>
          <span>帮助中心</span>
        </footer>
      </section>

      <section className="auth-panel">
        <div className="mobile-brand">
          <span className="logo-mark" aria-hidden="true">
            C
          </span>
          <span className="mobile-brand-name">cli-starter 统一身份认证</span>
        </div>
        <div className="auth-card">{children}</div>
      </section>
    </div>
  );
}
