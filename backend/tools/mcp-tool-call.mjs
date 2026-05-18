import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

const toolName = process.argv[2];
const argsJson = readArgsJson();

if (!toolName) {
  console.error('缺少 toolName 参数');
  process.exit(2);
}

const projectRoot = process.env.MCP_PROJECT_ROOT || 'D:/TestMCP/grant-mcp-main';
const serverCommand = process.env.MCP_SERVER_COMMAND || 'node';
const serverEntry = process.env.MCP_SERVER_ENTRY || path.join(projectRoot, 'dist', 'index.js');
const sdkDistRoot = path.join(projectRoot, 'node_modules', '@modelcontextprotocol', 'sdk', 'dist');

const [{ Client }, { StdioClientTransport }] = await Promise.all([
  import(pathToFileURL(path.join(sdkDistRoot, 'client', 'index.js')).href),
  import(pathToFileURL(path.join(sdkDistRoot, 'client', 'stdio.js')).href)
]);

function extractPayload(result) {
  const textBlock = result?.content?.find((item) => item?.type === 'text' && typeof item.text === 'string');
  if (!textBlock?.text) {
    return JSON.stringify(result ?? {});
  }
  return textBlock.text;
}

function readArgsJson() {
  const encoded = process.env.MCP_TOOL_ARGS_BASE64;
  if (encoded) {
    return Buffer.from(encoded, 'base64').toString('utf8');
  }
  return process.argv[3] || '{}';
}

const transport = new StdioClientTransport({
  command: serverCommand,
  args: [serverEntry],
  stderr: 'inherit'
});

const client = new Client(
  { name: 'design-1-backend-bridge', version: '0.1.0' },
  { capabilities: {} }
);

try {
  await client.connect(transport);
  const result = await client.callTool({
    name: toolName,
    arguments: JSON.parse(argsJson)
  });
  process.stdout.write(extractPayload(result));
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  await transport.close();
}
