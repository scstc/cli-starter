import { useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from '../api/client';
import { LockIcon, PhoneIcon } from './icons';

type Provider = 'wechat' | 'qq' | 'weibo';

const PROVIDER_META: Record<Provider, { label: string; color: string }> = {
  wechat: { label: '微信', color: '#07c160' },
  qq: { label: 'QQ', color: '#12b7f5' },
  weibo: { label: '微博', color: '#e6162d' },
};

function WeiboIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M10.1 8.6c-3.9.4-6.9 3-6.6 5.9.3 2.9 3.6 4.9 7.5 4.5 3.9-.4 6.9-3 6.6-5.9-.3-2.9-3.6-4.9-7.5-4.5zm-.4 7.9c-1.9.2-3.5-.7-3.7-2-.1-1.3 1.2-2.5 3.1-2.7 1.9-.2 3.5.7 3.7 2 .1 1.3-1.2 2.5-3.1 2.7zm4.6-6.3c-.4-.1-.6-.5-.5-.9.1-.4.5-.6.9-.5 1.3.4 2.1 1.6 1.9 2.9-.1.4-.4.7-.8.6-.4-.1-.7-.4-.6-.8.1-.5-.2-.9-.6-1.1l-.3-.2zM20.9 6.2c-.9-1.9-2.9-3-5.1-2.8-.5 0-.8.4-.8.9s.4.8.9.8c1.6-.1 3.1.7 3.8 2.2.3.6.4 1.2.3 1.8-.1.5.2.9.7 1 .5.1.9-.2 1-.7.2-1.1 0-2.2-.8-3.2z" />
    </svg>
  );
}

function WeChatIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M9.5 4C5.36 4 2 6.69 2 10c0 1.89 1.08 3.56 2.78 4.66l-.7 2.1 2.45-1.23c.93.3 1.93.47 2.97.47.28 0 .55-.01.82-.04A5.4 5.4 0 0 1 10 14c0-3.31 3.13-6 7-6 .34 0 .67.02 1 .06C17.18 5.72 13.62 4 9.5 4zM7 9a1 1 0 1 1 0-2 1 1 0 0 1 0 2zm5 0a1 1 0 1 1 0-2 1 1 0 0 1 0 2z" />
      <path d="M22 14c0-2.76-2.69-5-6-5s-6 2.24-6 5 2.69 5 6 5c.9 0 1.76-.15 2.54-.42L20.9 19.7l-.55-1.66C21.34 17.13 22 15.63 22 14zm-8-1a.9.9 0 1 1 0-1.8.9.9 0 0 1 0 1.8zm4 0a.9.9 0 1 1 0-1.8.9.9 0 0 1 0 1.8z" />
    </svg>
  );
}

function QQIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 2C8.7 2 6 4.58 6 7.75c0 .6-.05 1.2-.15 1.75-.6.9-1.6 2.5-1.85 3.9-.15.85.1 1.35.55 1.45.4.1.85-.1 1.25-.55.25.75.65 1.45 1.2 2.05C6 17 5 17.85 5 18.5c0 1.2 3.13 2.5 7 2.5s7-1.3 7-2.5c0-.65-1-1.5-2-2.15.55-.6.95-1.3 1.2-2.05.4.45.85.65 1.25.55.45-.1.7-.6.55-1.45-.25-1.4-1.25-3-1.85-3.9-.1-.55-.15-1.15-.15-1.75C18 4.58 15.3 2 12 2z" />
    </svg>
  );
}

function errText(e: unknown, fallback: string): string {
  if (e instanceof ApiError) {
    if (e.status === 0) return '无法连接认证服务,请确认后端已启动';
    return e.message || fallback;
  }
  return fallback;
}

/**
 * 登录卡底部的第三方登录区:微信 / QQ / 微博(demo 模拟授权)与运营商一键登录(demo 通道)。
 * 真实接入时替换 oauthAuthorize/callback 为厂商跳转 + SDK 回调即可,UI 不变。
 */
export default function ThirdPartyLogin({
  onToken,
  onError,
}: {
  onToken: (token: string) => void;
  onError: (text: string) => void;
}) {
  const [modal, setModal] = useState<Provider | 'oneclick' | null>(null);
  const [busy, setBusy] = useState(false);
  const [nickname, setNickname] = useState('');
  const [oneClickPreview, setOneClickPreview] = useState<{ token: string; maskedPhone: string } | null>(
    null,
  );

  async function openProvider(p: Provider) {
    setModal(p);
    setNickname('');
    // 默认昵称由服务端返回(微信用户/QQ用户/微博用户)
    try {
      const r = await api.oauthAuthorize(p);
      setNickname(r.nickname);
    } catch (e) {
      setModal(null);
      onError(errText(e, '发起授权失败'));
    }
  }

  async function confirmProvider(ev: FormEvent) {
    ev.preventDefault();
    if (!modal || modal === 'oneclick') return;
    const provider: Provider = modal;
    setBusy(true);
    try {
      // 昵称可改:改了就换票(同 provider+昵称 = 同账号)
      const auth = await api.oauthAuthorize(provider, nickname.trim() || undefined);
      const info = await api.oauthCallback(provider, auth.ticket);
      setModal(null);
      onToken(info.tokenValue);
    } catch (e) {
      onError(errText(e, '登录失败'));
      setModal(null);
    } finally {
      setBusy(false);
    }
  }

  async function startOneClick() {
    setModal('oneclick');
    setBusy(true);
    try {
      setOneClickPreview(await api.oneclickPreview());
    } catch (e) {
      setModal(null);
      onError(errText(e, '未检测到本机号码'));
    } finally {
      setBusy(false);
    }
  }

  async function confirmOneClick() {
    if (!oneClickPreview) return;
    setBusy(true);
    try {
      const info = await api.oneclickLogin(oneClickPreview.token);
      setModal(null);
      onToken(info.tokenValue);
    } catch (e) {
      setModal(null);
      onError(errText(e, '一键登录失败'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="oauth-divider">其他登录方式</div>
      <div className="oauth-row">
        <button type="button" className="oauth-btn" onClick={() => void openProvider('wechat')}>
          <span className="o-ico wechat">
            <WeChatIcon className="o-svg" />
          </span>
          微信登录
        </button>
        <button type="button" className="oauth-btn" onClick={() => void openProvider('qq')}>
          <span className="o-ico qq">
            <QQIcon className="o-svg" />
          </span>
          QQ登录
        </button>
        <button type="button" className="oauth-btn" onClick={() => void openProvider('weibo')}>
          <span className="o-ico weibo">
            <WeiboIcon className="o-svg" />
          </span>
          微博登录
        </button>
        <button type="button" className="oauth-btn" onClick={() => void startOneClick()}>
          <span className="o-ico carrier">
            <PhoneIcon className="o-svg" />
          </span>
          一键登录
        </button>
      </div>

      {modal === 'wechat' || modal === 'qq' ? (
        <div className="modal-overlay" onClick={() => setModal(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head" style={{ background: PROVIDER_META[modal].color }}>
              {modal === 'wechat' ? <WeChatIcon className="m-ico" /> : <QQIcon className="m-ico" />}
              {PROVIDER_META[modal].label}安全授权
            </div>
            <form
              className="modal-body"
              onSubmit={(e) => {
                void confirmProvider(e);
              }}
            >
              <p className="modal-note">演示环境模拟授权流程;真实接入将跳转 {PROVIDER_META[modal].label} 授权页。</p>
              <label className="modal-label" htmlFor="oauth-nick">
                授权昵称(同昵称登录同一账号)
              </label>
              <div className="field">
                <input
                  id="oauth-nick"
                  placeholder="昵称"
                  value={nickname}
                  onChange={(e) => setNickname(e.target.value)}
                />
              </div>
              <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
                {busy ? '授权中...' : '确认授权'}
              </button>
            </form>
          </div>
        </div>
      ) : null}

      {modal === 'oneclick' ? (
        <div className="modal-overlay" onClick={() => setModal(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head carrier-head">
              <PhoneIcon className="m-ico" />
              运营商本机号码一键登录
            </div>
            <div className="modal-body">
              {oneClickPreview ? (
                <>
                  <p className="modal-note">已自动识别本机号码(演示环境为模拟 SIM 卡):</p>
                  <div className="oneclick-phone">{oneClickPreview.maskedPhone}</div>
                  <button
                    type="button"
                    className="btn btn-primary btn-block"
                    disabled={busy}
                    onClick={() => void confirmOneClick()}
                  >
                    {busy ? '登录中...' : '本机号码一键登录'}
                  </button>
                </>
              ) : (
                <p className="modal-note">正在识别本机号码…</p>
              )}
              <p className="modal-hint">
                <LockIcon className="hint-ico" />
                运营商 SDK 换号过程不出示密码,token 一次性且 2 分钟内有效
              </p>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
