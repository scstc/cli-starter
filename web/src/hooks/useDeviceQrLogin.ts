import { useCallback, useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { api } from '../api/client';

export type QrState = 'loading' | 'waiting' | 'expired' | 'denied';

export interface DeviceQr {
  /** 二维码图片(data URI),内容为预填 user_code 的授权页地址 */
  image: string;
  state: QrState;
}

/**
 * 扫码登录:创建设备码授权,把授权页地址编码成二维码,轮询授权结果。
 * 手机扫码 → 在手机上登录并确认 → 本浏览器轮询到 ok,自动登录。
 *
 * 注意:二维码指向 starter.device-code.web-base;跨设备扫码需把该地址配置为
 * 局域网可达的主机名(127.0.0.1 仅本机可达)。
 */
export function useDeviceQrLogin(
  active: boolean,
  onToken: (token: string) => void,
): DeviceQr & { refresh: () => void } {
  const [image, setImage] = useState('');
  const [state, setState] = useState<QrState>('loading');
  const grantRef = useRef<{ deviceCode: string } | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // onToken/active 走 ref,避免调用方内联回调导致的 effect 重建与二维码反复生成
  const onTokenRef = useRef(onToken);
  onTokenRef.current = onToken;
  const activeRef = useRef(active);

  const stopPolling = useCallback(() => {
    if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const start = useCallback(async () => {
    stopPolling();
    setState('loading');
    setImage('');
    try {
      const info = await api.deviceCodeCreate();
      const url = info.verificationUriComplete || info.verificationUri;
      const img = await QRCode.toDataURL(url, {
        width: 220,
        margin: 1,
        color: { dark: '#1f2430', light: '#ffffff' },
      });
      if (!activeRef.current) return; // 已切走:丢弃这次授权
      grantRef.current = { deviceCode: info.deviceCode };
      setImage(img);
      setState('waiting');
      timerRef.current = setInterval(async () => {
        const grant = grantRef.current;
        if (!grant || !activeRef.current) return;
        try {
          const poll = await api.deviceTokenPoll(grant.deviceCode);
          if (poll.status === 'ok' && poll.token) {
            stopPolling();
            onTokenRef.current(poll.token.tokenValue);
          } else if (poll.status === 'expired' || poll.status === 'invalid') {
            stopPolling();
            setState('expired');
          } else if (poll.status === 'denied') {
            stopPolling();
            setState('denied');
          }
        } catch {
          // 网络抖动:保持轮询,不终止登录流程
        }
      }, Math.max(1, info.interval || 3) * 1000);
    } catch {
      setState('expired');
    }
  }, [stopPolling]);

  useEffect(() => {
    activeRef.current = active;
    if (active) {
      void start();
    } else {
      stopPolling();
    }
    return stopPolling;
  }, [active, start]);

  return { image, state, refresh: () => void start() };
}
