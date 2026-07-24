/**
 * Thin HTTP client for the Device Bridge REST API. Reads BRIDGE_URL + BRIDGE_TOKEN
 * from the environment, attaches the bearer token, and normalizes errors so tool
 * handlers stay small. Node 18+ global fetch — no HTTP dependency.
 *
 * This process runs on the operator's trusted machine (where the agent runs), holds
 * the token, and only calls routes that already require it. It adds no attack surface
 * to the phone.
 */

export interface ImageResult {
  base64: string;
  mimeType: string;
}

export class BridgeError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = "BridgeError";
  }
}

export class BridgeClient {
  private readonly baseUrl: string;
  private readonly token: string;

  constructor() {
    const url = process.env.BRIDGE_URL?.trim();
    const token = process.env.BRIDGE_TOKEN?.trim();
    if (!url) {
      throw new Error(
        "BRIDGE_URL is not set. Set it to your Device Bridge endpoint (e.g. http://127.0.0.1:8765). " +
          "The app's Remote tab → 'Copy connection config' gives you BRIDGE_URL and BRIDGE_TOKEN.",
      );
    }
    if (!token) {
      throw new Error(
        "BRIDGE_TOKEN is not set. Copy it from the app's Remote tab (token card or 'Copy connection config').",
      );
    }
    // Normalize: strip a trailing slash so path joins are clean.
    this.baseUrl = url.replace(/\/+$/, "");
    this.token = token;
  }

  private headers(extra?: Record<string, string>): Record<string, string> {
    return {
      Authorization: `Bearer ${this.token}`,
      ...extra,
    };
  }

  /** GET a JSON endpoint. Returns the parsed body. */
  async getJson(path: string): Promise<unknown> {
    const res = await this.fetchWithTimeout(`${this.baseUrl}${path}`, {
      method: "GET",
      headers: this.headers(),
    });
    return this.parseJson(res, path);
  }

  /** POST a JSON body to an endpoint. Returns the parsed body. */
  async postJson(path: string, body?: unknown): Promise<unknown> {
    const res = await this.fetchWithTimeout(`${this.baseUrl}${path}`, {
      method: "POST",
      headers: this.headers({ "Content-Type": "application/json" }),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return this.parseJson(res, path);
  }

  /** GET a binary image endpoint. Returns base64 + mime for MCP image content. */
  async getImage(path: string): Promise<ImageResult> {
    const res = await this.fetchWithTimeout(`${this.baseUrl}${path}`, {
      method: "GET",
      headers: this.headers(),
    });
    if (!res.ok) {
      throw new BridgeError(await this.errorText(res, path), res.status);
    }
    const mimeType = res.headers.get("content-type") ?? "image/jpeg";
    const buf = Buffer.from(await res.arrayBuffer());
    return { base64: buf.toString("base64"), mimeType };
  }

  private async parseJson(res: Response, path: string): Promise<unknown> {
    const text = await res.text();
    if (!res.ok) {
      // Surface the server's error body if present, else a generic message.
      let detail = text;
      try {
        const j = JSON.parse(text);
        detail = j?.error?.message ?? j?.message ?? text;
      } catch {
        /* keep raw text */
      }
      throw new BridgeError(`${path} → HTTP ${res.status}: ${detail || res.statusText}`, res.status);
    }
    if (!text) return { ok: true };
    try {
      return JSON.parse(text);
    } catch {
      return { raw: text };
    }
  }

  private async errorText(res: Response, path: string): Promise<string> {
    const t = await res.text().catch(() => "");
    return `${path} → HTTP ${res.status}: ${t || res.statusText}`;
  }

  private async fetchWithTimeout(url: string, init: RequestInit, timeoutMs = 15000): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetch(url, { ...init, signal: controller.signal });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      throw new BridgeError(
        `Cannot reach the bridge at ${this.baseUrl} (${msg}). Is the bridge running and the URL/token correct?`,
      );
    } finally {
      clearTimeout(timer);
    }
  }
}
