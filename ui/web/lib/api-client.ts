const getBaseUrl = () =>
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:4000";

const defaultHeaders: HeadersInit = {
  "Content-Type": "application/json",
};

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly body?: unknown
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function handleResponse<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));

  if (!res.ok) {
    const message =
      (typeof body === "object" && body !== null && "error" in body &&
       typeof (body as { error: unknown }).error === "string")
        ? (body as { error: string }).error
        : res.statusText || "Request failed";
    throw new ApiError(message, res.status, body);
  }

  return body as T;
}

type RequestConfig = RequestInit & { params?: Record<string, string> };

async function request<T>(
  path: string,
  config: RequestConfig = {}
): Promise<T> {
  const { params, ...init } = config;
  const url = new URL(path, getBaseUrl());
  if (params) {
    Object.entries(params).forEach(([k, v]) =>
      url.searchParams.set(k, v)
    );
  }
  const res = await fetch(url.toString(), {
    ...init,
    headers: { ...defaultHeaders, ...init.headers },
  });
  return handleResponse<T>(res);
}

export const fetchClient = {
  get<T>(path: string, config?: RequestConfig): Promise<T> {
    return request<T>(path, { ...config, method: "GET" });
  },

  post<T>(path: string, body?: unknown, config?: RequestConfig): Promise<T> {
    return request<T>(path, {
      ...config,
      method: "POST",
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  },

  put<T>(path: string, body?: unknown, config?: RequestConfig): Promise<T> {
    return request<T>(path, {
      ...config,
      method: "PUT",
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  },

  delete<T>(path: string, config?: RequestConfig): Promise<T> {
    return request<T>(path, { ...config, method: "DELETE" });
  },
};
