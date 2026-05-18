import process from 'node:process';
import { execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';

const execFile = promisify(execFileCallback);

const DEFAULTS = {
  apiBase: process.env.NETEASE_API_BASE_URL || 'http://127.0.0.1:3000',
  dbHost: process.env.MYSQL_HOST || '127.0.0.1',
  dbPort: process.env.MYSQL_PORT || '3306',
  dbName: process.env.MYSQL_DATABASE || 'design',
  dbUser: process.env.MYSQL_USER || 'root',
  dbPassword: process.env.MYSQL_PASSWORD || '123456',
  mysqlBin: process.env.MYSQL_BIN || 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
  limit: 50,
  overwrite: false,
  dryRun: false,
  startId: 0
};

function parseArgs(argv) {
  const options = { ...DEFAULTS };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = argv[index + 1];

    if (arg === '--limit' && next) {
      options.limit = Number(next) || DEFAULTS.limit;
      index += 1;
      continue;
    }
    if (arg === '--api-base' && next) {
      options.apiBase = next;
      index += 1;
      continue;
    }
    if (arg === '--db-host' && next) {
      options.dbHost = next;
      index += 1;
      continue;
    }
    if (arg === '--db-port' && next) {
      options.dbPort = next;
      index += 1;
      continue;
    }
    if (arg === '--db-name' && next) {
      options.dbName = next;
      index += 1;
      continue;
    }
    if (arg === '--db-user' && next) {
      options.dbUser = next;
      index += 1;
      continue;
    }
    if (arg === '--db-password' && next) {
      options.dbPassword = next;
      index += 1;
      continue;
    }
    if (arg === '--mysql-bin' && next) {
      options.mysqlBin = next;
      index += 1;
      continue;
    }
    if (arg === '--start-id' && next) {
      options.startId = Number(next) || 0;
      index += 1;
      continue;
    }
    if (arg === '--overwrite') {
      options.overwrite = true;
      continue;
    }
    if (arg === '--dry-run') {
      options.dryRun = true;
      continue;
    }
    if (arg === '--help' || arg === '-h') {
      printHelp();
      process.exit(0);
    }
  }

  return options;
}

function printHelp() {
  console.log(`
Usage:
  node artist-description-sync.mjs [options]

Options:
  --limit <n>          Number of artists to process. Default: 50
  --start-id <id>      Only process artists with id >= value
  --overwrite          Update all matched artists, not only empty descriptions
  --dry-run            Fetch data but do not write to MySQL
  --api-base <url>     Netease API base URL. Default: http://127.0.0.1:3000
  --db-host <host>     MySQL host. Default: 127.0.0.1
  --db-port <port>     MySQL port. Default: 3306
  --db-name <name>     Database name. Default: design
  --db-user <user>     MySQL user. Default: root
  --db-password <pwd>  MySQL password. Default: 123456
  --mysql-bin <path>   mysql executable path
`);
}

function mysqlArgs(options, sql) {
  return [
    `--host=${options.dbHost}`,
    `--port=${options.dbPort}`,
    `--user=${options.dbUser}`,
    `--password=${options.dbPassword}`,
    '--default-character-set=utf8mb4',
    '--batch',
    '--raw',
    '--skip-column-names',
    options.dbName,
    '-e',
    sql
  ];
}

async function runMysql(options, sql) {
  const { stdout } = await execFile(options.mysqlBin, mysqlArgs(options, sql), {
    windowsHide: true,
    maxBuffer: 16 * 1024 * 1024
  });
  return stdout ?? '';
}

function sqlString(value) {
  if (value == null) return 'NULL';
  return `'${String(value)
    .replace(/\\/g, '\\\\')
    .replace(/'/g, "''")
    .replace(/\r/g, '\\r')
    .replace(/\n/g, '\\n')}'`;
}

async function loadArtists(options) {
  const whereParts = [`id >= ${Math.max(0, Number(options.startId) || 0)}`];
  if (!options.overwrite) {
    whereParts.push(`(description IS NULL OR TRIM(description) = '')`);
  }

  const sql = `
    SELECT id, name
    FROM artists
    WHERE ${whereParts.join(' AND ')}
    ORDER BY id ASC
    LIMIT ${Math.max(1, Number(options.limit) || DEFAULTS.limit)}
  `;

  const stdout = await runMysql(options, sql);
  return stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [id, ...rest] = line.split('\t');
      return {
        id: Number(id),
        name: rest.join('\t').trim()
      };
    })
    .filter((item) => Number.isFinite(item.id));
}

function extractDescription(payload) {
  const brief = typeof payload?.briefDesc === 'string' ? payload.briefDesc.trim() : '';
  if (brief) return brief;

  const introductions = Array.isArray(payload?.introduction) ? payload.introduction : [];
  const merged = introductions
    .map((item) => [item?.ti, item?.txt].filter(Boolean).join('：').trim())
    .filter(Boolean)
    .join('\n\n')
    .trim();

  return merged;
}

async function fetchArtistDescription(options, artistId) {
  const url = new URL('/artist/desc', options.apiBase);
  url.searchParams.set('id', String(artistId));

  const response = await fetch(url, {
    headers: {
      Accept: 'application/json'
    }
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  const payload = await response.json();
  if (payload?.code !== 200) {
    throw new Error(`API code ${payload?.code ?? 'unknown'}`);
  }

  return {
    payload,
    description: extractDescription(payload)
  };
}

async function updateArtistDescription(options, artistId, description) {
  const sql = `
    UPDATE artists
    SET description = ${sqlString(description)}
    WHERE id = ${Number(artistId)}
  `;
  await runMysql(options, sql);
}

async function main() {
  const options = parseArgs(process.argv.slice(2));

  console.log('=== Artist Description Sync ===');
  console.log(`API Base: ${options.apiBase}`);
  console.log(`Database: ${options.dbUser}@${options.dbHost}:${options.dbPort}/${options.dbName}`);
  console.log(`Mode: ${options.dryRun ? 'DRY RUN' : 'WRITE'}`);

  const artists = await loadArtists(options);
  console.log(`Artists queued: ${artists.length}`);

  let successCount = 0;
  let skippedCount = 0;
  let failedCount = 0;

  for (const artist of artists) {
    try {
      const { description } = await fetchArtistDescription(options, artist.id);
      if (!description) {
        skippedCount += 1;
        console.log(`[SKIP] ${artist.id} ${artist.name} -> empty description`);
        continue;
      }

      if (options.dryRun) {
        successCount += 1;
        console.log(`[DRY] ${artist.id} ${artist.name} -> ${description.slice(0, 80)}${description.length > 80 ? '...' : ''}`);
        continue;
      }

      await updateArtistDescription(options, artist.id, description);
      successCount += 1;
      console.log(`[OK] ${artist.id} ${artist.name}`);
    } catch (error) {
      failedCount += 1;
      console.error(`[FAIL] ${artist.id} ${artist.name} -> ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  console.log('\n=== Summary ===');
  console.log(`Success: ${successCount}`);
  console.log(`Skipped: ${skippedCount}`);
  console.log(`Failed: ${failedCount}`);
}

main().catch((error) => {
  console.error('\nScript failed:');
  console.error(error);
  process.exitCode = 1;
});
