import { useEffect, useState } from 'react';

/** 媒体查询 hook:视口是否命中查询(默认 <=920px 视为手机/窄屏)。 */
export function useMediaQuery(query = '(max-width: 920px)'): boolean {
  const [matches, setMatches] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(query).matches,
  );

  useEffect(() => {
    const mq = window.matchMedia(query);
    const onChange = (e: MediaQueryListEvent) => setMatches(e.matches);
    mq.addEventListener('change', onChange);
    setMatches(mq.matches);
    return () => mq.removeEventListener('change', onChange);
  }, [query]);

  return matches;
}
