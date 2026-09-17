import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useCountdown } from '../hooks/useCountdown';
import { useDeviceQrLogin } from '../hooks/useDeviceQrLogin';
import { useMediaQuery } from '../hooks/useMediaQuery';
import ThirdPartyLogin from '../components/ThirdPartyLogin';
import { LockIcon, PhoneIcon, QrIcon, RefreshIcon, ShieldIcon, UserIcon } from '../components/icons';

type Tab = 'password' | 'sms' | 'qr';

function errText(e: unknown, fallback: string): string {
  if (e instanceof ApiError) {
    if (e.status === 0) return '无法连接认证服务,请确认后端已启动';
    return e.message || fallback;
  }
  return fallback;
}

export default function LoginPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { token, signIn } = useAuth();

  const [tab, setTab] = useState<Tab>('password');
  const [busy, setBusy] = useState(false);
  const [banner, setBanner] = useState<{ text: string; kind: 'banner-ok' | 'banner-err' } | null>(
    null,
  );

  // 账号密码 + 图形验证码
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [captchaId, setCaptchaId] = useState<string | null>(null);
  const [captchaImg, setCaptchaImg] = useState('');
  const [captchaCode, setCaptchaCode] = useState('');

  // 手机号 + 短信验证码
  const [phone, setPhone] = useState('');
  const [smsCode, setSmsCode] = useState('');
  const [countdown, triggerCountdown] = useCountdown(60);

  // 扫码登录
  const qr = useDeviceQrLogin(tab === 'qr', (t) => signIn(t));
  const isMobile = useMediaQuery();

  // 仅接受站内路径,防开放跳转
  const redirectTarget = (() => {
    const r = params.get('redirect') || '/';
    return r.startsWith('/') && !r.startsWith('//') ? r : '/';
  })();

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

  async function submitPassword(ev: FormEvent) {
    ev.preventDefault();
    if (!captchaId) {
      fail('请先获取图形验证码');
      return;
    }
    setBusy(true);
    setBanner(null);
    try {
      const info = await api.login(username.trim(), password, captchaId, captchaCode.trim());
      signIn(info.tokenValue);
      navigate(redirectTarget, { replace: true });
    } catch (e) {
      // 验证码一次性消费:失败(含密码错误)后必须换一张
      void refreshCaptcha();
      fail(errText(e, '登录失败'));
    } finally {
      setBusy(false);
    }
  }

  async function sendSms() {
    if (!/^\d{11}$/.test(phone.trim())) {
      fail('请输入 11 位手机号');
      return;
    }
    setBanner(null);
    try {
      const r = await api.smsSend(phone.trim(), 'login');
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

  async function submitSms(ev: FormEvent) {
    ev.preventDefault();
    setBusy(true);
    setBanner(null);
    try {
      const info = await api.smsLogin(phone.trim(), smsCode.trim());
      signIn(info.tokenValue);
      navigate(redirectTarget, { replace: true });
    } catch (e) {
      fail(errText(e, '登录失败'));
    } finally {
      setBusy(false);
    }
  }

  // 已登录直接回跳目标页
  if (token) {
    return <Navigate to={redirectTarget} replace />;
  }

  const tabs: Array<{ key: Tab; label: string; icon: typeof UserIcon }> = [
    { key: 'password', label: '账号密码', icon: UserIcon },
    { key: 'sms', label: '手机验证码', icon: PhoneIcon },
    { key: 'qr', label: '扫码登录', icon: QrIcon },
  ];

  return (
    <>
      <h2 className="card-title">欢迎登录</h2>
      <p className="card-sub">登录以授权 CLI 设备或获取访问 Token</p>

      {banner && <div className={`banner ${banner.kind}`}>{banner.text}</div>}

      <div className="tabs" role="tablist">
        {tabs.map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            type="button"
            className={`tab ${tab === key ? 'active' : ''}`}
            onClick={() => setTab(key)}
          >
            <Icon className="tab-icon" />
            {label}
          </button>
        ))}
      </div>

      {tab === 'password' && (
        <form
          onSubmit={(e) => {
            void submitPassword(e);
          }}
        >
          <div className="field">
            <UserIcon className="field-icon" />
            <input
              aria-label="用户名"
              placeholder="用户名"
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
              placeholder="密码"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
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
          <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
            {busy ? '登录中...' : '登 录'}
          </button>
        </form>
      )}

      {tab === 'sms' && (
        <form
          onSubmit={(e) => {
            void submitSms(e);
          }}
        >
          <div className="field">
            <PhoneIcon className="field-icon" />
            <input
              aria-label="手机号"
              className="mono"
              maxLength={11}
              autoComplete="tel"
              inputMode="numeric"
              placeholder="手机号"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
            />
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
            {busy ? '登录中...' : '登 录'}
          </button>
        </form>
      )}

      {tab === 'qr' && (
        <div className="qr-wrap">
          <div className={`qr-box ${qr.state !== 'waiting' ? 'qr-masked' : ''}`}>
            {qr.image && <img className="qr-img" src={qr.image} alt="登录二维码" />}
            {qr.state === 'loading' && <div className="qr-tip">正在生成二维码…</div>}
            {qr.state === 'expired' && (
              <button type="button" className="qr-mask" onClick={qr.refresh}>
                二维码已过期
                <br />
                点击刷新
              </button>
            )}
            {qr.state === 'denied' && (
              <button type="button" className="qr-mask" onClick={qr.refresh}>
                已取消登录
                <br />
                点击刷新重试
              </button>
            )}
          </div>
          <p className="qr-tip">
            {qr.state === 'waiting'
              ? '请使用手机扫描二维码,并在手机上完成登录确认'
              : '使用手机相机扫码,在手机上登录后即可自动登录本机'}
          </p>
          {isMobile && (
            <p className="qr-mobile-tip">
              手机屏幕上的二维码无法被本机扫描:手机端建议使用上方「账号密码 / 手机验证码」
              登录;如需授权 CLI 等其他设备,登录后到控制台输入其用户码即可。
            </p>
          )}
        </div>
      )}

      <p className="card-foot">
        没有账号?
        <Link to="/register">立即注册</Link>
      </p>

      <ThirdPartyLogin
        onToken={(t) => signIn(t)}
        onError={(text) => fail(text)}
      />
    </>
  );
}
