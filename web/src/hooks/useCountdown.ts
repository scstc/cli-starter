import { useEffect, useState } from 'react';

/** 倒计时 hook:trigger() 开始 seconds 秒递减,归零后可再次触发。 */
export function useCountdown(seconds: number): [number, () => void] {
  const [left, setLeft] = useState(0);

  useEffect(() => {
    if (left <= 0) return;
    const t = setTimeout(() => setLeft((c) => c - 1), 1000);
    return () => clearTimeout(t);
  }, [left]);

  const trigger = () => setLeft(seconds);
  return [left, trigger];
}
