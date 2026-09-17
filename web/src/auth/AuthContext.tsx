import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { api } from '../api/client';
import type { UserInfo } from '../api/client';

const TOKEN_KEY = 'cs_token';

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY);
}

interface AuthContextValue {
  token: string | null;
  user: UserInfo | null;
  loading: boolean;
  /** 登录成功后调用:写入 token 并触发用户信息加载 */
  signIn: (token: string) => void;
  /** 只清本地会话副本,不调服务端登出——避免把已复制给 CLI 的 token 作废 */
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue>({
  token: getToken(),
  user: null,
  loading: false,
  signIn: () => {},
  logout: () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(getToken);
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!token) {
      setUser(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    api
      .me()
      .then((u) => {
        if (!cancelled) setUser(u);
      })
      .catch(() => {
        // 会话失效:清 token,由路由守卫接管跳登录页
        if (!cancelled) {
          sessionStorage.removeItem(TOKEN_KEY);
          setTokenState(null);
          setUser(null);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  const signIn = (t: string) => {
    sessionStorage.setItem(TOKEN_KEY, t);
    setTokenState(t);
  };

  const logout = () => {
    sessionStorage.removeItem(TOKEN_KEY);
    setTokenState(null);
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ token, user, loading, signIn, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  return useContext(AuthContext);
}
