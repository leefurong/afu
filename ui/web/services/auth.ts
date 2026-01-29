import { fetchClient } from "@/lib/api-client";

const TOKEN_KEY = "token";

export type LoginRequest = {
  username: string;
  password: string;
};

export type LoginResponse = {
  token: string;
  message?: string;
};

export function setToken(token: string): void {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function removeToken(): void {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(TOKEN_KEY);
}

export async function login(credentials: LoginRequest): Promise<LoginResponse> {
  const data = await fetchClient.post<LoginResponse>(
    "/api/login",
    credentials
  );
  if (data.token) {
    setToken(data.token);
  }
  return data;
}
