import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

const projectRoot = 'D:/TestMCP/grant-mcp-main';
const sdkDistRoot = path.join(projectRoot, 'node_modules', '@modelcontextprotocol', 'sdk', 'dist');
const serverEntry = process.env.MCP_SERVER_ENTRY || path.join(projectRoot, 'dist', 'index.js');
const serverCommand = process.env.MCP_SERVER_COMMAND || 'node';

const [{ Client }, { StdioClientTransport }] = await Promise.all([
  import(pathToFileURL(path.join(sdkDistRoot, 'client', 'index.js')).href),
  import(pathToFileURL(path.join(sdkDistRoot, 'client', 'stdio.js')).href)
]);

function parseTextContent(result) {
  const textBlock = result?.content?.find((item) => item?.type === 'text' && typeof item.text === 'string');
  if (!textBlock?.text) {
    return { ok: false, error: 'MCP 返回中没有可解析的 text 内容', raw: result };
  }

  try {
    return { ok: true, data: JSON.parse(textBlock.text), raw: result };
  } catch (error) {
    return {
      ok: false,
      error: `text 内容不是合法 JSON: ${error instanceof Error ? error.message : String(error)}`,
      text: textBlock.text,
      raw: result
    };
  }
}

function printSection(title) {
  console.log(`\n=== ${title} ===`);
}

function printJson(label, value) {
  console.log(`${label}:`);
  console.log(JSON.stringify(value, null, 2));
}

function validateToolList(toolResponse) {
  const tools = toolResponse?.tools ?? [];
  const expected = [
    'search_library',
    'get_recommendations',
    'analyze_genre',
    'find_similar_genres',
    'get_listening_history',
    'library_stats'
  ];

  const missing = expected.filter((name) => !tools.some((tool) => tool?.name === name));
  return {
    total: tools.length,
    expected,
    missing,
    passed: missing.length === 0
  };
}

function validateSearchLibrary(payload) {
  return {
    passed: typeof payload?.count === 'number' && Array.isArray(payload?.results),
    count: payload?.count,
    sampleKeys: payload?.results?.[0] ? Object.keys(payload.results[0]) : []
  };
}

function validateRecommendations(payload) {
  return {
    passed: typeof payload?.count === 'number' && Array.isArray(payload?.recommendations),
    count: payload?.count,
    queryKeys: payload?.query ? Object.keys(payload.query) : [],
    sampleKeys: payload?.recommendations?.[0] ? Object.keys(payload.recommendations[0]) : []
  };
}

function validateListeningHistory(payload) {
  return {
    passed:
      Array.isArray(payload?.recent_plays) &&
      typeof payload?.period_days === 'number' &&
      typeof payload?.total_plays === 'number',
    totalPlays: payload?.total_plays,
    sampleKeys: payload?.recent_plays?.[0] ? Object.keys(payload.recent_plays[0]) : []
  };
}

function validateAnalyzeGenre(payload) {
  return {
    passed:
      typeof payload?.genre === 'string' &&
      Array.isArray(payload?.descriptors) &&
      Array.isArray(payload?.related_genres),
    genre: payload?.genre,
    descriptorCount: payload?.descriptors?.length ?? 0
  };
}

function validateFindSimilarGenres(payload) {
  return {
    passed: typeof payload?.genre === 'string' && Array.isArray(payload?.similar_genres),
    genre: payload?.genre,
    similarCount: payload?.similar_genres?.length ?? 0
  };
}

function validateLibraryStats(payload) {
  return {
    passed: payload && typeof payload === 'object' && !Array.isArray(payload),
    keys: payload ? Object.keys(payload) : []
  };
}

async function callToolAndValidate(client, toolName, args, validator) {
  printSection(`调用 ${toolName}`);
  printJson('请求参数', args);

  const rawResult = await client.callTool({ name: toolName, arguments: args ?? {} });
  const parsed = parseTextContent(rawResult);

  if (!parsed.ok) {
    printJson('原始返回', parsed.raw);
    throw new Error(`${toolName} 解析失败: ${parsed.error}`);
  }

  printJson('解析结果', parsed.data);
  const validation = validator(parsed.data);
  printJson('校验结果', validation);

  if (!validation.passed) {
    throw new Error(`${toolName} 返回结构不符合预期`);
  }

  return parsed.data;
}

async function main() {
  const transport = new StdioClientTransport({
    command: serverCommand,
    args: [serverEntry],
    stderr: 'inherit'
  });

  const client = new Client(
    { name: 'design-1-mcp-validator', version: '0.1.0' },
    { capabilities: {} }
  );

  await client.connect(transport);

  try {
    printSection('列出 Tools');
    const toolList = await client.listTools();
    printJson('Tools', toolList);
    const listValidation = validateToolList(toolList);
    printJson('Tool 校验', listValidation);

    if (!listValidation.passed) {
      throw new Error(`缺少工具: ${listValidation.missing.join(', ')}`);
    }

    await callToolAndValidate(
      client,
      'search_library',
      { genres: ['jazz'], min_rating: 4, max_results: 5 },
      validateSearchLibrary
    );

    await callToolAndValidate(
      client,
      'get_recommendations',
      {
        similar_to: ['Burial - Untrue'],
        genres: ['electronic', 'ambient'],
        min_rating: 3,
        max_results: 5,
        hybrid_weight: 0.7
      },
      validateRecommendations
    );

    await callToolAndValidate(
      client,
      'analyze_genre',
      { genre: 'ambient' },
      validateAnalyzeGenre
    );

    await callToolAndValidate(
      client,
      'find_similar_genres',
      { genre: 'ambient', max_results: 5 },
      validateFindSimilarGenres
    );

    await callToolAndValidate(
      client,
      'get_listening_history',
      { limit: 10, days_back: 30 },
      validateListeningHistory
    );

    await callToolAndValidate(
      client,
      'library_stats',
      {},
      validateLibraryStats
    );

    printSection('验证完成');
    console.log('所有 MCP 工具已完成基础契约校验。');
  } finally {
    await transport.close();
  }
}

main().catch((error) => {
  console.error('\nMCP 验证失败:');
  console.error(error);
  process.exitCode = 1;
});
