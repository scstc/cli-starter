import { useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';

export default function ConsolePage() {
  const location = useLocation();
  const [params] = useSearchParams();
  const { token, user, loading, logout } = useAuth();

  const [userCode, setUserCode] = useState(params.get('user_code')?.toUpperCase() ?? '');
  const [result, setResult] = useState<{ text: string; kind: 'banner-ok' | 'banner-err' } | null>(
    null,
  );
  const [showToken, setShowToken] = useState(false);
  const [copied, setCopied] = useState(false);

  // 未登录 → 登录页,登录后跳回本页(保留 user_code 参数)
  if (!token) {
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }

  function fail(text: string) {
    setResult({ text, kind: 'banner-err' });
  }

  async function decide(action: 'authorize' | 'deny') {
    const compact = userCode.replace(/[^0-9A-Za-z]/g, '').toUpperCase();
    if (compact.length !== 8) {
      fail('请输入 CLI 显示的 8 位用户码,例如 BDMK-MJHT。');
      return;
    }
    try {
      await (action === 'authorize' ? api.deviceAuthorize(compact) : api.deviceDeny(compact));
      setResult({
        text:
          action === 'authorize'
            ? '已授权,正在等待 CLI 领取 token —— 请回到终端查看。'
            : '已拒绝,该设备本次登录已终止。',
        kind: action === 'authorize' ? 'banner-ok' : 'banner-err',
      });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        // 会话失效:清 token,由上方守卫跳转登录页
        logout();
        return;
      }
      fail(e instanceof ApiError ? e.message : '请求失败');
    }
  }

  function onCodeInput(value: string) {
    const compact = value.replace(/[^0-9A-Za-z]/g, '').toUpperCase().slice(0, 8);
    setUserCode(compact.length > 4 ? `${compact.slice(0, 4)}-${compact.slice(4)}` : compact);
  }

  function onCodeEnter(ev: KeyboardEvent<HTMLInputElement>) {
    if (ev.key === 'Enter') void decide('authorize');
  }

  async function copyToken() {
    if (!token) return;
    try {
      await navigator.clipboard.writeText(token);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setShowToken(true);
      setResult({ text: '复制失败,请手动选择并复制已展开的 Token。', kind: 'banner-err' });
    }
  }

  return (
    <>
      <header className="topbar">
        <span className="logo-mark" aria-hidden="true">
          C
        </span>
        <span className="brand">cli-starter 统一身份认证</span>
        <span className="spacer" />
        <span className="user-chip">
          {loading ? '加载中…' : user ? `${user.username}(${user.roles.join(', ')})` : ''}
        </span>
        <button type="button" className="btn btn-ghost" onClick={logout}>
          退出登录
        </button>
      </header>

      <main className="wrap">
        <section className="card" id="device-card">
          <h2>设备码授权</h2>
          <p className="desc">CLI 正在等待授权:输入它显示的用户码并确认,该设备即以你的身份登录。</p>
          <div className="row">
            <input
              className="code-input"
              placeholder="XXXX-XXXX"
              maxLength={9}
              autoComplete="off"
              spellCheck={false}
              value={userCode}
              onChange={(e) => onCodeInput(e.target.value)}
              onKeyDown={onCodeEnter}
            />
            <button type="button" className="btn btn-primary" onClick={() => void decide('authorize')}>
              确认授权
            </button>
            <button type="button" className="btn btn-danger" onClick={() => void decide('deny')}>
              拒绝
            </button>
          </div>
          {result && (
            <div className={`banner ${result.kind}`} style={{ margin: '16px 0 0' }}>
              {result.text}
            </div>
          )}
        </section>

        <section className="card" id="token-card">
          <h2>访问 Token</h2>
          <p className="desc">
            在本页创建的 Token 可直接粘贴给 CLI 完成登录:
            <code>cli-starter auth login --with-token</code>(回车后粘贴)
          </p>
          <div className="row">
            <code className="token-box">{showToken ? token : '······'}</code>
            <button type="button" className="btn" onClick={() => setShowToken((v) => !v)}>
              {showToken ? '隐藏' : '显示'}
            </button>
            <button type="button" className="btn" onClick={() => void copyToken()}>
              {copied ? '已复制' : '复制'}
            </button>
          </div>
        </section>
      </main>
    </>
  );
}
