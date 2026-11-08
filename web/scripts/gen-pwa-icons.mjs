// 生成 PWA PNG 图标（无外部依赖：手写 PNG 编码 + zlib）。
// 设计：品牌蓝底（#1677ff）白色 "M" 字形（两竖 + 中 V），内容落在中心 50% 安全区（maskable 可用）。
// 用法: node scripts/gen-pwa-icons.mjs [输出目录=public]
import { deflateSync } from 'node:zlib'
import { writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'

const BG = [0x16, 0x77, 0xff]
const FG = [0xff, 0xff, 0xff]

function crc32(buf) {
  let table = crc32.table
  if (!table) {
    table = crc32.table = new Int32Array(256)
    for (let n = 0; n < 256; n++) {
      let c = n
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
      table[n] = c
    }
  }
  let c = -1
  for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ -1) >>> 0
}

function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}

function encodePng(size, pixels) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 2 // color type RGB
  const raw = Buffer.alloc(size * (size * 3 + 1))
  for (let y = 0; y < size; y++) {
    raw[y * (size * 3 + 1)] = 0 // filter: none
    for (let x = 0; x < size; x++) {
      const c = pixels[y * size + x]
      const o = y * (size * 3 + 1) + 1 + x * 3
      raw[o] = c[0]
      raw[o + 1] = c[1]
      raw[o + 2] = c[2]
    }
  }
  return Buffer.concat([
    sig,
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

/** 画 "M"：x∈[0.25w,0.75w]，y 从 0.75h 向上到 0.25h+|x-0.5w|（倒 V 下沿） */
function drawM(size) {
  const pixels = new Array(size * size).fill(BG)
  const w = size
  const x0 = Math.round(w * 0.25)
  const x1 = Math.round(w * 0.75)
  const yTop = Math.round(w * 0.28)
  const yBottom = Math.round(w * 0.72)
  const mid = w / 2
  for (let x = x0; x <= x1; x++) {
    // 倒 V 下沿：|x-mid| 越大越高
    const vBottom = Math.round(yTop + (Math.abs(x - mid) / (mid - x0)) * (yBottom - yTop) * 0.55)
    for (let y = vBottom; y <= yBottom; y++) {
      pixels[y * w + x] = FG
    }
  }
  return pixels
}

function main() {
  const outDir = process.argv[2] ?? join(process.cwd(), 'public')
  mkdirSync(outDir, { recursive: true })
  for (const size of [192, 512]) {
    const png = encodePng(size, drawM(size))
    writeFileSync(join(outDir, `pwa-${size}.png`), png)
    console.log(`已生成 ${join(outDir, `pwa-${size}.png`)}（${png.length} 字节）`)
  }
}

main()
