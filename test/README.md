# test 目录说明

这个目录用于存放与 `backend`、`frontend` 并列的独立验证脚本。

当前已提供：

- `mcp-contract-validator.mjs`
  - 通过 MCP SDK 的 `stdio client` 方式直接拉起你的 MCP 服务
  - 会依次验证：
    - `search_library`
    - `get_recommendations`
    - `analyze_genre`
    - `find_similar_genres`
    - `get_listening_history`
    - `library_stats`

运行方式：

```bash
node C:\Users\LIUHENGRU\Desktop\design-1\test\mcp-contract-validator.mjs
```

如果需要自定义 MCP 服务入口：

```bash
$env:MCP_SERVER_ENTRY='D:\TestMCP\grant-mcp-main\dist\index.js'
node C:\Users\LIUHENGRU\Desktop\design-1\test\mcp-contract-validator.mjs
```

如果需要自定义启动命令：

```bash
$env:MCP_SERVER_COMMAND='node'
$env:MCP_SERVER_ENTRY='D:\TestMCP\grant-mcp-main\dist\index.js'
node C:\Users\LIUHENGRU\Desktop\design-1\test\mcp-contract-validator.mjs
```

说明：

- 默认启动命令是：
  - `node D:\TestMCP\grant-mcp-main\dist\index.js`
- 脚本依赖你 MCP 工程里的：
  - `D:\TestMCP\grant-mcp-main\node_modules\@modelcontextprotocol\sdk`
