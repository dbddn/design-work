import http from 'node:http';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

const projectRoot = process.env.MCP_PROJECT_ROOT || 'D:/TestMCP/grant-mcp-main';
const serverCommand = process.env.MCP_SERVER_COMMAND || 'node';
const serverEntry = process.env.MCP_SERVER_ENTRY || path.join(projectRoot, 'dist', 'index.js');
const sdkDistRoot = path.join(projectRoot, 'node_modules', '@modelcontextprotocol', 'sdk', 'dist');
const bridgePort = Number.parseInt(process.env.MCP_BRIDGE_PORT || '9101', 10);

const [{ Client }, { StdioClientTransport }] = await Promise.all([
  import(pathToFileURL(path.join(sdkDistRoot, 'client', 'index.js')).href),
  import(pathToFileURL(path.join(sdkDistRoot, 'client', 'stdio.js')).href)
]);

let transport = null;
let client = null;
let connectingPromise = null;

function extractPayload(result) {
  const textBlock = result?.content?.find((item) => item?.type === 'text' && typeof item.text === 'string');
  if (!textBlock?.text) {
    return JSON.stringify(result ?? {});
  }
  return textBlock.text;
}

async function ensureClient() {
  if (client && transport) {
    return client;
  }
  if (connectingPromise) {
    return connectingPromise;
  }

  connectingPromise = (async () => {
    transport = new StdioClientTransport({
      command: serverCommand,
      args: [serverEntry],
      stderr: 'inherit'
    });
    client = new Client(
      { name: 'design-1-backend-daemon', version: '0.1.0' },
      { capabilities: {} }
    );
    await client.connect(transport);
    return client;
  })();

  try {
    return await connectingPromise;
  } finally {
    connectingPromise = null;
  }
}

async function resetClient() {
  try {
    if (transport) {
      await transport.close();
    }
  } catch {}
  transport = null;
  client = null;
}

async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  const body = Buffer.concat(chunks).toString('utf8');
  return body ? JSON.parse(body) : {};
}

function writeJson(res, statusCode, payload) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(payload));
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    writeJson(res, 200, { ok: true });
    return;
  }

  if (req.method !== 'POST' || req.url !== '/call') {
    writeJson(res, 404, { ok: false, error: 'Not Found' });
    return;
  }

  try {
    const body = await readJson(req);
    const toolName = body?.toolName;
    const payload = body?.payload ?? {};
    if (!toolName) {
      writeJson(res, 400, { ok: false, error: 'toolName is required' });
      return;
    }

    const activeClient = await ensureClient();
    const result = await activeClient.callTool({
      name: toolName,
      arguments: payload
    });

    writeJson(res, 200, { ok: true, stdout: extractPayload(result) });
  } catch (error) {
    await resetClient();
    writeJson(res, 500, {
      ok: false,
      error: error instanceof Error ? error.message : String(error)
    });
  }
});

server.listen(bridgePort, '127.0.0.1');

const shutdown = async () => {
  server.close();
  await resetClient();
  process.exit(0);
};

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
