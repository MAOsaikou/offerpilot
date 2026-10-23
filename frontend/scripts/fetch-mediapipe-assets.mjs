import { copyFile, mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const sdkVersion = '0.10.32'
const frontend = fileURLToPath(new URL('../', import.meta.url))
const destination = join(frontend, 'public/mediapipe')
const installed = join(frontend, 'node_modules/@mediapipe/tasks-vision')
const force = process.argv.includes('--force')
const models = 'https://storage.googleapis.com/mediapipe-models'

async function nonempty(path) {
  try {
    const info = await stat(path)
    return info.isFile() && info.size > 0
  } catch (error) {
    if (error.code === 'ENOENT') return false
    throw error
  }
}

async function copyAsset(relative) {
  const target = join(destination, relative)
  if (!force && (await nonempty(target))) {
    console.log(`[skip] ${relative}`)
    return
  }
  const source = join(installed, relative)
  if (!(await nonempty(source)))
    throw new Error(`Installed SDK asset missing or empty: ${relative}`)
  await mkdir(dirname(target), { recursive: true })
  await copyFile(source, target)
  console.log(`[copy] ${relative}`)
}

async function copyDirectory(relative) {
  for (const entry of await readdir(join(installed, relative), { withFileTypes: true })) {
    const child = join(relative, entry.name)
    if (entry.isDirectory()) await copyDirectory(child)
    else if (entry.isFile()) await copyAsset(child)
  }
}

async function downloadAsset(filename, url) {
  const target = join(destination, filename)
  if (!force && (await nonempty(target))) {
    console.log(`[skip] ${filename}`)
    return
  }
  console.log(`[download] ${filename}`)
  const response = await fetch(url, { signal: AbortSignal.timeout(120_000) })
  if (!response.ok) throw new Error(`${filename}: HTTP ${response.status}`)
  const bytes = new Uint8Array(await response.arrayBuffer())
  if (!bytes.length) throw new Error(`${filename}: empty response`)
  const temporary = `${target}.download`
  try {
    await writeFile(temporary, bytes)
    await rename(temporary, target)
  } finally {
    await rm(temporary, { force: true })
  }
  console.log(`[saved] ${filename} (${bytes.length} bytes)`)
}

async function main() {
  if (process.argv.slice(2).some((argument) => argument !== '--force')) {
    throw new Error('Usage: pnpm mediapipe:fetch [--force]')
  }
  const manifest = JSON.parse(await readFile(join(frontend, 'package.json'), 'utf8'))
  if (manifest.dependencies['@mediapipe/tasks-vision'] !== sdkVersion) {
    throw new Error(`Expected package.json to pin @mediapipe/tasks-vision to ${sdkVersion}`)
  }
  let sdk
  try {
    sdk = JSON.parse(await readFile(join(installed, 'package.json'), 'utf8'))
  } catch (error) {
    if (error.code !== 'ENOENT') throw error
    throw new Error('MediaPipe SDK is not installed. Run pnpm -C frontend install first.', {
      cause: error,
    })
  }
  if (sdk.version !== sdkVersion) {
    throw new Error(
      `Installed MediaPipe SDK is ${sdk.version}; expected ${sdkVersion}. Run pnpm install.`,
    )
  }
  console.log(`MediaPipe ${sdkVersion} → ${destination}${force ? ' (--force)' : ''}`)
  await mkdir(destination, { recursive: true })
  await copyAsset('vision_bundle.mjs')
  await copyDirectory('wasm')
  await downloadAsset(
    'face_landmarker.task',
    `${models}/face_landmarker/face_landmarker/float16/1/face_landmarker.task`,
  )
  await downloadAsset(
    'pose_landmarker_lite.task',
    `${models}/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task`,
  )
  console.log('Ready: VITE_MEDIAPIPE_ASSETS_URL=/mediapipe (assets are gitignored).')
}

main().catch((error) => {
  console.error(`[error] ${error.message}`)
  process.exitCode = 1
})
