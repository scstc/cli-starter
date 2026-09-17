import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useCountdown } from '../hooks/useCountdown';
import { LockIcon, PhoneIcon, RefreshIcon, ShieldIcon, UserIcon } from '../components/icons';

function errText(e: unknown, fallback: string): string {
  if (e instanceof ApiError) {
    if (e.status === 0) return '无法连接认证服务,请确认后端已启动';
    return e.message || fallback;
  }
  return fallback;
}

export default function RegisterPage() {
  const navigate = useNavigate();
  const { token, signIn } = useAuth();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [phone, setPhone] = useState('');
  const [smsCode, setSmsCode] = useState('');
  const [captchaId, setCaptchaId] = useState<string | null>(null);
  const [captchaImg, setCaptchaImg] = useState('');
  const [captchaCode, setCaptchaCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [banner, setBanner] = useState<{ text: string; kind: 'banner-ok' | 'banner-err' } | null>(
    null,
  );
  const [countdown, triggerCountdown] = useCountdown(60);

  function fail(text: string) {
    setBanner({ text, kind: 'banner-err' });
  }

  async function refreshCaptcha() {
    setCaptchaId(null);
    setCaptchaImg('');
    setCaptchaCode('');
    try {
      const c = await api.captcha();
      setCaptchaId(c.captchaId);
      setCaptchaImg(c.image);
    } catch (e) {
      fail(errText(e, '获取验证码失败'));
    }
  }

  useEffect(() => {
    void refreshCaptcha();
  }, []);

  async function sendSms() {
    if (!/^\d{11}$/.test(phone.trim())) {
      fail('请输入 11 位手机号');
      return;
    }
    setBanner(null);
    try {
      const r = await api.smsSend(phone.trim(), 'register');
      triggerCountdown();
      setBanner({
        text: r.debugCode
          ? `已发送。演示模式回显验证码:${r.debugCode}(生产环境无此回显)`
          : '已发送,请查收短信',
        kind: 'banner-ok',
      });
    } catch (e) {
      fail(errText(e, '发送失败'));
    }
  }

  async function submit(ev: FormEvent) {
    ev.preventDefault();
    if (!captchaId) {
      fail('请先获取图形验证码');
      return;
    }
    setBusy(true);
    setBanner(null);
    try {
      const info = await api.register({
        username: username.trim(),
        password,
        phone: phone.trim(),
        captchaId,
        captchaCode: captchaCode.trim(),
        smsCode: smsCode.trim(),
      });
      signIn(info.tokenValue);
      navigate('/', { replace: true });
    } catch (e) {
      // 验证码一次性消费:失败后必须换一张
      void refreshCaptcha();
      fail(errText(e, '注册失败'));
    } finally {
      setBusy(false);
    }
  }

  // 已登录直接回控制台
  if (token) {
    return <Navigate to="/" replace />;
  }

  return (
    <>
      <h2 className="card-title">注册新账号</h2>
      <p className="card-sub">手机号将用于短信登录,注册后自动登录</p>

      {banner && <div className={`banner ${banner.kind}`}>{banner.text}</div>}

      <form
        onSubmit={(e) => {
          void submit(e);
        }}
      >
        <div className="field">
          <UserIcon className="field-icon" />
          <input
            aria-label="用户名"
            placeholder="用户名(3-32 位字母/数字/下划线)"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div className="field">
          <LockIcon className="field-icon" />
          <input
            aria-label="密码"
            type="password"
            placeholder="密码(6-64 位)"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <div className="field">
          <PhoneIcon className="field-icon" />
          <input
            aria-label="手机号"
            className="mono"
            maxLength={11}
            autoComplete="tel"
            inputMode="numeric"
            placeholder="手机号(1 开头 11 位)"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
          />
        </div>
        <div className="field captcha-field">
          <ShieldIcon className="field-icon" />
          <input
            aria-label="图形验证码"
            className="mono"
            maxLength={4}
            autoComplete="off"
            spellCheck={false}
            placeholder="图形验证码"
            style={{ textTransform: 'uppercase' }}
            value={captchaCode}
            onChange={(e) => setCaptchaCode(e.target.value)}
          />
          {captchaImg ? (
            <img
              className="captcha-img"
              src={captchaImg}
              alt="验证码,点击刷新"
              title="看不清?点击刷新"
              onClick={() => void refreshCaptcha()}
            />
          ) : (
            <span className="captcha-img captcha-loading" />
          )}
          <button
            type="button"
            className="icon-btn"
            title="刷新验证码"
            onClick={() => void refreshCaptcha()}
          >
            <RefreshIcon className="field-icon" />
          </button>
        </div>
        <div className="field captcha-field">
          <ShieldIcon className="field-icon" />
          <input
            aria-label="短信验证码"
            className="mono"
            maxLength={6}
            autoComplete="one-time-code"
            inputMode="numeric"
            placeholder="短信验证码"
            value={smsCode}
            onChange={(e) => setSmsCode(e.target.value)}
          />
          <button
            type="button"
            className="send-btn"
            disabled={countdown > 0}
            onClick={() => void sendSms()}
          >
            {countdown > 0 ? `${countdown}s 后重发` : '获取验证码'}
          </button>
        </div>
        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? '注册中...' : '注册并登录'}
        </button>
      </form>

      <p className="card-foot">
        已有账号?
        <Link to="/login">去登录</Link>
      </p>
    </>
  );
}
