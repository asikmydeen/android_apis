/**
 * Curated MCP tools mapping to Device Bridge /v1 routes. Descriptions are written
 * for the model that will choose them — they are the interface, so they say what the
 * tool does and when to reach for it. Read tools return JSON as text; capture_photo
 * returns an image. Act tools require the bridge's mutating token (already carried by
 * the client).
 */
import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { BridgeClient, BridgeError } from "./client.js";

type TextContent = { type: "text"; text: string };
type ImageContent = { type: "image"; data: string; mimeType: string };
type ToolResult = { content: Array<TextContent | ImageContent>; isError?: boolean };

function text(value: unknown): ToolResult {
  const body = typeof value === "string" ? value : JSON.stringify(value, null, 2);
  return { content: [{ type: "text", text: body }] };
}

function fail(e: unknown): ToolResult {
  const msg = e instanceof BridgeError ? e.message : e instanceof Error ? e.message : String(e);
  return { content: [{ type: "text", text: `Error: ${msg}` }], isError: true };
}

export function registerTools(server: McpServer, client: BridgeClient): void {
  // ---- Meta ----
  server.registerTool(
    "get_capabilities",
    {
      description:
        "List what this phone can do right now — hardware present (cameras, sensors), and which permissions are granted. Call this first to learn the device's abilities before planning other tool calls.",
      inputSchema: {},
    },
    async () => {
      try {
        return text(await client.getJson("/v1/capabilities"));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "get_health",
    {
      description:
        "Check the bridge is alive and healthy: version, uptime, and whether any collector is degraded. Use to confirm connectivity before other calls.",
      inputSchema: {},
    },
    async () => {
      try {
        return text(await client.getJson("/v1/health"));
      } catch (e) {
        return fail(e);
      }
    },
  );

  // ---- Read: sensors & state ----
  server.registerTool(
    "get_snapshot",
    {
      description:
        "One-shot read of ALL live device signals at once — location, battery, network, audio level, latest sensors, USB. The most efficient way to see the whole current state in a single call.",
      inputSchema: {},
    },
    async () => {
      try {
        return text(await client.getJson("/v1/snapshot"));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "get_location",
    {
      description:
        "Get the phone's last known GPS location (latitude, longitude, accuracy, provider, and whether the fix is stale). Requires location permission granted on the device.",
      inputSchema: {},
    },
    async () => {
      try {
        return text(await client.getJson("/v1/location"));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "get_battery",
    {
      description: "Get battery percentage and charging status.",
      inputSchema: {},
    },
    async () => {
      try {
        return text(await client.getJson("/v1/battery"));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "get_network",
    {
      description: "Get network state: transport type (wifi/cellular) and whether the phone is connected.",
      inputSchema: {},
    },
    async () => {
      try {
        return text(await client.getJson("/v1/network"));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "get_sensors",
    {
      description:
        "Get the latest readings from all reporting motion/environment sensors (accelerometer, gyroscope, light, step counter, etc.). Pass a type to read one sensor.",
      inputSchema: {
        type: z
          .string()
          .optional()
          .describe("Optional sensor type name (e.g. 'android.sensor.accelerometer'). Omit for all sensors."),
      },
    },
    async ({ type }: { type?: string }) => {
      try {
        const path = type ? `/v1/sensors/${encodeURIComponent(type)}` : "/v1/sensors";
        return text(await client.getJson(path));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "get_audio_level",
    {
      description:
        "Get the current microphone loudness (RMS and peak in dB). This is a LEVEL, not audio content — use it to detect sound/silence or how loud the room is. Requires the microphone collector enabled.",
      inputSchema: {},
    },
    async () => {
      try {
        return text(await client.getJson("/v1/audio"));
      } catch (e) {
        return fail(e);
      }
    },
  );

  // ---- Read: cameras & USB ----
  server.registerTool(
    "list_cameras",
    {
      description: "List the phone's cameras (id, facing, hardware level) so you can choose one to capture from.",
      inputSchema: {},
    },
    async () => {
      try {
        return text(await client.getJson("/v1/cameras"));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "capture_photo",
    {
      description:
        "Capture a still photo from a camera and return it as an image. Use list_cameras first to pick an id (0 is usually the back camera). Requires camera permission.",
      inputSchema: {
        camera_id: z.string().default("0").describe("Camera id to capture from (default '0')."),
      },
    },
    async ({ camera_id }: { camera_id?: string }) => {
      try {
        const id = camera_id ?? "0";
        await client.postJson(`/v1/camera/${encodeURIComponent(id)}/capture`);
        // The capture endpoint triggers the shot; the JPEG is served at last.jpg.
        const img = await client.getImage("/v1/camera/last.jpg");
        return { content: [{ type: "image", data: img.base64, mimeType: img.mimeType }] };
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "list_usb_devices",
    {
      description: "List USB devices attached to the phone (via OTG), including whether each is likely serial or storage.",
      inputSchema: {},
    },
    async () => {
      try {
        return text(await client.getJson("/v1/usb/devices"));
      } catch (e) {
        return fail(e);
      }
    },
  );

  // ---- Act: operate the phone ----
  server.registerTool(
    "launch_app",
    {
      description:
        "Open an installed app by its Android package name (e.g. 'com.google.android.apps.maps'). Use this to bring an app to the foreground before other actions.",
      inputSchema: {
        package: z.string().describe("Android package name of the app to launch."),
      },
    },
    async ({ package: pkg }: { package: string }) => {
      try {
        return text(await client.postJson("/v1/app/launch", { package: pkg }));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "open_url",
    {
      description:
        "Open a URL or deep link on the phone (fires an Android VIEW intent). Works for https links, geo: coordinates, tel:, mailto:, and app deep links.",
      inputSchema: {
        url: z.string().describe("The URI to open, e.g. 'https://example.com' or 'geo:37.42,-122.08'."),
      },
    },
    async ({ url }: { url: string }) => {
      try {
        return text(await client.postJson("/v1/intent", { action: "android.intent.action.VIEW", uri: url }));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "speak",
    {
      description: "Make the phone speak text aloud using text-to-speech. The agent's voice in the physical room.",
      inputSchema: {
        text: z.string().describe("The text to speak aloud."),
      },
    },
    async ({ text: toSpeak }: { text: string }) => {
      try {
        return text(await client.postJson("/v1/tts/speak", { text: toSpeak }));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "set_torch",
    {
      description: "Turn the phone's flashlight (torch) on or off.",
      inputSchema: {
        on: z.boolean().describe("true to turn the torch on, false to turn it off."),
      },
    },
    async ({ on }: { on: boolean }) => {
      try {
        return text(await client.postJson("/v1/torch", { on }));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "vibrate",
    {
      description: "Vibrate the phone for a number of milliseconds (useful to get someone's attention).",
      inputSchema: {
        ms: z.number().int().min(1).max(10000).default(200).describe("Vibration duration in milliseconds (1–10000)."),
      },
    },
    async ({ ms }: { ms?: number }) => {
      try {
        return text(await client.postJson("/v1/vibrate", { ms: ms ?? 200 }));
      } catch (e) {
        return fail(e);
      }
    },
  );

  server.registerTool(
    "notify",
    {
      description: "Post a local notification on the phone with a title and body.",
      inputSchema: {
        title: z.string().default("Device Bridge").describe("Notification title."),
        body: z.string().describe("Notification body text."),
      },
    },
    async ({ title, body }: { title?: string; body: string }) => {
      try {
        return text(await client.postJson("/v1/notify", { title: title ?? "Device Bridge", body }));
      } catch (e) {
        return fail(e);
      }
    },
  );
}
