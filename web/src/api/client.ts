/** 后端 API 客户端:统一 base、envelope 拆包、satoken 头、错误归一化。 */

const BASE = (import.meta.env.VITE_API_BASE ?? 'http://127.0.0.1:8090').replace(/\/+$/, '');

export interface SaTokenInfo {
  tokenName: string;
  tokenValue: string;
  loginId?: string;
  tokenTimeout?: number;
}

export interface UserInfo {
  loginId: string;
  username: string;
  roles: string[];
  device: string;
}

export interface CaptchaInfo {
  captchaId: string;
  /** PNG data URI,可直接 <img src> */
  image: string;
  /** 仅服务端 debug-echo=true(demo 模式)回显答案,生产为空 */
  debugCode?: string;
}

export interface SmsSendData {
  debugCode?: string;
}

export interface DeviceCodeInfo {
  deviceCode: string;
  userCode: string;
  verificationUri: string;
  verificationUriComplete: string;
  expiresIn: number;
  interval: number;
}

export interface DeviceTokenData {
  status: 'pending' | 'ok' | 'denied' | 'expired' | 'invalid' | string;
  token?: SaTokenInfo;
}

/** 后端拒绝/网络不可达。status=0 表示网络层失败;businessCode 为统一包裹里的 code(414/415/416/417…)。 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly businessCode?: number,
    message?: string,
  ) {
    super(message ?? `HTTP ${status}`);
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
  /** 无鉴权端点(登录/验证码/短信)传 false,不带 satoken 头 */
  auth?: boolean;
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true } = opts;
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = sessionStorage.getItem('cs_token');
  if (auth && token) headers.satoken = token;

  let resp: Response;
  try {
    resp = await fetch(BASE + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, undefined, '无法连接认证服务');
  }

  const envelope = (await resp.json().catch(() => null)) as
    | { code?: number; msg?: string; data?: T }
    | null;
  if (!envelope || typeof envelope.code !== 'number') {
    throw new ApiError(resp.status, undefined, `响应解析失败(HTTP ${resp.status})`);
  }
  if (resp.status === 401 || envelope.code === 401) {
    throw new ApiError(resp.status, 401, envelope.msg || '未登录或登录已过期');
  }
  if (resp.status < 200 || resp.status >= 300 || envelope.code !== 200) {
    throw new ApiError(resp.status, envelope.code, envelope.msg || `请求失败(HTTP ${resp.status})`);
  }
  return envelope.data as T;
}

export const api = {
  captcha: () => request<CaptchaInfo>('/api/auth/captcha', { auth: false }),
  login: (username: string, password: string, captchaId: string, captchaCode: string) =>
    request<SaTokenInfo>('/api/auth/login', {
      method: 'POST',
      auth: false,
      body: { username, password, captchaId, captchaCode },
    }),
  /** 自主注册:成功即自动登录,返回 SaTokenInfo */
  register: (payload: {
    username: string;
    password: string;
    phone: string;
    captchaId: string;
    captchaCode: string;
    smsCode: string;
  }) =>
    request<SaTokenInfo>('/api/auth/register', {
      method: 'POST',
      auth: false,
      body: payload,
    }),
  /** purpose: login=手机号须已注册 / register=手机号须未注册,验证码不跨场景复用 */
  smsSend: (phone: string, purpose: 'login' | 'register' = 'login') =>
    request<SmsSendData>('/api/auth/sms/send', {
      method: 'POST',
      auth: false,
      body: { phone, purpose },
    }),
  smsLogin: (phone: string, code: string) =>
    request<SaTokenInfo>('/api/auth/sms/login', {
      method: 'POST',
      auth: false,
      body: { phone, code },
    }),
  /** 第三方登录(demo 模拟授权):签发授权票 */
  oauthAuthorize: (provider: 'wechat' | 'qq' | 'weibo', nickname?: string) =>
    request<{ ticket: string; nickname: string }>(`/api/auth/oauth/${provider}/authorize`, {
      method: 'POST',
      auth: false,
      body: { nickname },
    }),
  /** 第三方登录:授权票换登录态(首次自动建号并绑定身份) */
  oauthCallback: (provider: 'wechat' | 'qq' | 'weibo', ticket: string) =>
    request<SaTokenInfo>(`/api/auth/oauth/${provider}/callback`, {
      method: 'POST',
      auth: false,
      body: { ticket },
    }),
  /** 运营商一键登录(demo):预览本机号码并取一次性 token */
  oneclickPreview: () =>
    request<{ token: string; maskedPhone: string }>('/api/auth/oneclick/preview', {
      method: 'GET',
      auth: false,
    }),
  /** 运营商一键登录:token 换登录态 */
  oneclickLogin: (token: string) =>
    request<SaTokenInfo>('/api/auth/oneclick/login', {
      method: 'POST',
      auth: false,
      body: { token },
    }),
  me: () => request<UserInfo>('/api/user/me'),
  /** 扫码登录:创建设备码授权(返回 verificationUriComplete 供生成二维码) */
  deviceCodeCreate: () => request<DeviceCodeInfo>('/api/auth/device/code', { method: 'POST' }),
  /** 扫码登录:轮询授权结果;ok 携带 token */
  deviceTokenPoll: (deviceCode: string) =>
    request<DeviceTokenData>('/api/auth/device/token', {
      method: 'POST',
      body: { deviceCode },
    }),
  deviceAuthorize: (userCode: string) =>
    request<null>('/api/auth/device/authorize', { method: 'POST', body: { userCode } }),
  deviceDeny: (userCode: string) =>
    request<null>('/api/auth/device/deny', { method: 'POST', body: { userCode } }),
};
