#!/usr/bin/env node
/**
 * Device Bridge MCP server.
 *
 * Exposes a Device Bridge (SensIO) phone to MCP-speaking AI agents (Claude Desktop,
 * Claude Code, Cursor, …) over stdio. It's a thin proxy: MCP tool calls become
 * authenticated requests to the phone's local /v1 REST API. Runs on the operator's
 * machine, reads BRIDGE_URL + BRIDGE_TOKEN from the environment.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { BridgeClient } from "./client.js";
import { registerTools } from "./tools.js";

function createClientOrExit(): BridgeClient {
  try {
    return new BridgeClient();
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    process.stderr.write(`[devicebridge-mcp] ${msg}\n`);
    process.exit(1);
  }
}

async function main(): Promise<void> {
  // Construct the client first so a missing URL/token fails fast with a clear message
  // on stderr (stdout is reserved for the MCP protocol stream).
  const client = createClientOrExit();

  const server = new McpServer({
    name: "devicebridge",
    version: "1.0.0",
  });

  registerTools(server, client);

  const transport = new StdioServerTransport();
  await server.connect(transport);
  process.stderr.write("[devicebridge-mcp] connected over stdio\n");
}

main().catch((e) => {
  process.stderr.write(`[devicebridge-mcp] fatal: ${e instanceof Error ? e.stack ?? e.message : String(e)}\n`);
  process.exit(1);
});
