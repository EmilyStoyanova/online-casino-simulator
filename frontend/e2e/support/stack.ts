/**
 * The isolated stack for the browser acceptance tests: a throwaway PostgreSQL container, the backend
 * jar and (started by Playwright's `webServer`) a Vite dev server. Nothing of the developer's local
 * environment is used: other ports, no `.env`, random throwaway secrets that are never printed.
 *
 * State (process id, secrets) is kept in a file in the system temp directory, so the tests can stop
 * and restart the backend (backend outage, short session timeout). It is deleted by the teardown.
 */
import { execFileSync, spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, openSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

export const BACKEND_PORT = 8091
export const FRONTEND_PORT = 5174
export const DB_PORT = 5441
/**
 * An IP address, not `localhost`: Vite would listen on `::1` only, and Chrome does not always try
 * `::1` for `localhost` (seen as intermittent ERR_CONNECTION_REFUSED).
 */
export const FRONTEND_HOST = '127.0.0.1'
export const FRONTEND_URL = `http://${FRONTEND_HOST}:${FRONTEND_PORT}`
/** The backend seen through IPv4 (the Vite proxy) and IPv6 (test fixtures): two client IPs for the throttle limits. */
export const BACKEND_IPV4 = `http://127.0.0.1:${BACKEND_PORT}`
export const BACKEND_IPV6 = `http://[::1]:${BACKEND_PORT}`

const CONTAINER = 'casino-e2e-pg'
const DIR = join(tmpdir(), 'casino-e2e')
const STATE = join(DIR, 'state.json')

interface StackState {
  dbPassword: string
  throttleSecret: string
  backendPid?: number
}

function readState(): StackState {
  return JSON.parse(readFileSync(STATE, 'utf8')) as StackState
}

function writeState(state: StackState): void {
  writeFileSync(STATE, JSON.stringify(state))
}

function backendJar(): string {
  const target = resolve(import.meta.dirname, '../../../backend/target')
  const jar = existsSync(target) ? readdirSync(target).find((f) => /^casino-simulator-backend-.*\.jar$/.test(f)) : undefined
  if (!jar) throw new Error('Backend jar missing: run `./mvnw -DskipTests package` in backend/ first')
  return join(target, jar)
}

function java(): string {
  const home = process.env.JAVA_HOME
  if (!home) throw new Error('JAVA_HOME must point to a JDK 21 (see README, "Java 21 for Git Bash")')
  return join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
}

async function waitFor(check: () => Promise<boolean>, what: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await check()) return
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error(`Timed out waiting for ${what}`)
}

async function backendUp(): Promise<boolean> {
  try {
    return (await fetch(`${BACKEND_IPV4}/actuator/health`)).ok
  } catch {
    return false
  }
}

export async function startDatabase(): Promise<void> {
  mkdirSync(DIR, { recursive: true })
  const state: StackState = { dbPassword: randomBytes(16).toString('hex'), throttleSecret: randomBytes(32).toString('base64') }
  writeState(state)
  execFileSync('docker', ['run', '-d', '--rm', '--name', CONTAINER, '-e', 'POSTGRES_DB=casino_e2e', '-e', 'POSTGRES_USER=e2e',
    '-e', `POSTGRES_PASSWORD=${state.dbPassword}`, '-p', `127.0.0.1:${DB_PORT}:5432`, 'postgres:17.11-alpine'], { stdio: 'ignore' })
  await waitFor(async () => {
    try {
      execFileSync('docker', ['exec', CONTAINER, 'pg_isready', '-U', 'e2e', '-d', 'casino_e2e'], { stdio: 'ignore' })
      return true
    } catch {
      return false
    }
  }, 'PostgreSQL', 60_000)
}

/** Starts the backend jar; `overrides` are extra environment variables (e.g. a short session timeout). */
export async function startBackend(overrides: Record<string, string> = {}): Promise<void> {
  const state = readState()
  const runDir = join(DIR, 'run') // empty: the backend must not import a developer `.env`
  mkdirSync(runDir, { recursive: true })
  const log = openSync(join(DIR, 'backend.log'), 'a')
  const child = spawn(java(), ['-jar', backendJar()], {
    cwd: runDir,
    detached: true,
    stdio: ['ignore', log, log],
    env: {
      ...process.env,
      DB_URL: `jdbc:postgresql://localhost:${DB_PORT}/casino_e2e`,
      DB_USERNAME: 'e2e',
      DB_PASSWORD: state.dbPassword,
      APP_SECURITY_THROTTLE_SECRET: state.throttleSecret,
      SERVER_PORT: String(BACKEND_PORT),
      APP_CORS_ALLOWED_ORIGINS: FRONTEND_URL,
      ...overrides,
    },
  })
  child.unref()
  writeState({ ...state, backendPid: child.pid })
  await waitFor(backendUp, 'the backend', 120_000)
}

export async function stopBackend(): Promise<void> {
  const state = readState()
  if (state.backendPid) {
    try {
      process.kill(state.backendPid)
    } catch {
      // Already gone.
    }
  }
  writeState({ ...state, backendPid: undefined })
  await waitFor(async () => !(await backendUp()), 'the backend to stop', 30_000)
}

/**
 * Runs one query against the throwaway database (psql inside the container) and returns the rows,
 * fields split at `|`. `vars` are psql variables, used in the query as `:'name'` (quoted literals).
 */
export function queryDatabase(sql: string, vars: Record<string, string> = {}): string[][] {
  const args = ['exec', '-i', CONTAINER, 'psql', '-U', 'e2e', '-d', 'casino_e2e', '-X', '-q', '-A', '-t', '-F', '|',
    '-v', 'ON_ERROR_STOP=1', ...Object.entries(vars).flatMap(([name, value]) => ['-v', `${name}=${value}`])]
  const output = execFileSync('docker', args, { input: sql, encoding: 'utf8' })
  return output.split(/\r?\n/).filter((line) => line !== '').map((line) => line.split('|'))
}

/** The backend log of this run (all starts), to check what the backend reported. */
export function backendLog(): string {
  const file = join(DIR, 'backend.log')
  return existsSync(file) ? readFileSync(file, 'utf8') : ''
}

export function stopDatabase(): void {
  try {
    execFileSync('docker', ['stop', CONTAINER], { stdio: 'ignore' })
  } catch {
    // Not running.
  }
}

export function removeState(): void {
  rmSync(STATE, { force: true })
}
