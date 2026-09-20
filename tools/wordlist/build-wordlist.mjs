#!/usr/bin/env node
/**
 * 词表转换器 —— 把公开的英语词表转成「App」能导入的 CSV。
 *
 * 用法（需要 Node 18+，自带 fetch）：
 *
 *   # 看看有哪些词书
 *   node build-wordlist.mjs --list
 *
 *   # 生成四级词汇（默认带音标、词性、例句）
 *   node build-wordlist.mjs --book 四级 --out cet4.csv
 *
 *   # 每 100 词切一个单元（App 里会出现「按课学习」）
 *   node build-wordlist.mjs --book 六级 --units 100 --out cet6.csv
 *
 *   # 不要例句（文件小一点）
 *   node build-wordlist.mjs --book 考研 --no-example --out kaoyan.csv
 *
 *   # 把本地已有的表格转成 App 的格式（支持 .jsonl / .json / .csv）
 *   node build-wordlist.mjs --file my-words.jsonl --out my-words.csv
 *
 * 生成后把 CSV 传到手机上，在 App 的「单词 → 导入」里选中它就行，不用重新打包。
 */
import { writeFileSync, readFileSync, existsSync } from 'node:fs'
import { basename } from 'node:path'

// ---------------------------------------------------------------- 词表来源

/**
 * 默认来源：GitHub 上的 KyleBing/english-vocabulary。
 * 它每条长这样，音标、词性、例句都齐：
 *   {"word":"abruptly","us":"ə'brʌptli","uk":"ə'brʌptlɪ",
 *    "translations":[{"translation":"突然地","type":"adv"}],
 *    "phrases":[],"sentences":[{"sentence":"The path ends off abruptly.","translation":"这条路突然到头了。"}]}
 */
const SOURCE_BASE =
  'https://raw.githubusercontent.com/KyleBing/english-vocabulary/master/full_line_jsonl/sentence/%E6%AD%A3%E5%BA%8F/'

/** 有例句的那个目录下有哪些词书（文件名去掉 .jsonl 就是 --book 的取值） */
const BOOKS = [
  '四级', '六级', '考研', '托福', 'SAT', 'GRE',
  '专四', '专八', '高中', '初中', '人教高中',
  '人教初中七年级', '人教初中八年级', '人教初中九年级',
  '人教小学三年级', '人教小学四年级', '人教小学五年级', '人教小学六年级',
]

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const args = { example: true, units: 0, limit: 0, book: '', file: '', out: '', list: false }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const next = () => argv[++i]
    switch (a) {
      case '--book': args.book = next() ?? ''; break
      case '--file': args.file = next() ?? ''; break
      case '--out': args.out = next() ?? ''; break
      case '--units': args.units = Number(next() ?? 0) || 0; break
      case '--limit': args.limit = Number(next() ?? 0) || 0; break
      case '--no-example': args.example = false; break
      case '--list': args.list = true; break
      case '-h':
      case '--help': args.help = true; break
      default:
        console.error(`不认识的参数：${a}（--help 看用法）`)
        process.exit(2)
    }
  }
  return args
}

const USAGE = `词表转换器 —— 生成「App」能导入的 CSV

  node build-wordlist.mjs --list                        列出可用的词书
  node build-wordlist.mjs --book <名字> [选项]          从网上下载并转换
  node build-wordlist.mjs --file <本地文件> [选项]      转换本地已有的表格

选项：
  --out <路径>      输出文件（默认 <词书>.csv）
  --units <数字>    每 N 个词切一个单元（默认 0 = 不切）
  --limit <数字>    最多要多少个词（默认 0 = 全部，调试用）
  --no-example      不要例句列（文件更小）
  -h, --help        看这段

生成的 CSV 第一行是列名，格式见 tools/wordlist/README.md。`

// ---------------------------------------------------------------- 词性码表

/** 源数据里的词性缩写 → 中文（跟 App 里德语词库的写法保持一致） */
const POS_MAP = {
  n: '名词', v: '动词', adj: '形容词', adv: '副词', prep: '介词', conj: '连词',
  pron: '代词', num: '数词', art: '冠词', int: '感叹词', aux: '助动词',
  vt: '动词', vi: '动词', modal: '助动词', det: '限定词', abbr: '缩写',
}

// ---------------------------------------------------------------- 下载

async function download(url, label) {
  for (let attempt = 1; attempt <= 5; attempt++) {
    try {
      const ac = new AbortController()
      const timer = setTimeout(() => ac.abort(), 120000)
      const res = await fetch(url, { signal: ac.signal })
      clearTimeout(timer)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const text = await res.text()
      if (!text.trim()) throw new Error('拿到的是空文件')
      return text
    } catch (e) {
      console.error(`  第 ${attempt} 次下载 ${label} 失败：${e.message}`)
      if (attempt === 5) throw new Error(`下载 ${label} 失败：${e.message}`)
      await new Promise((r) => setTimeout(r, 1500 * attempt))
    }
  }
}

// ---------------------------------------------------------------- 解析

function csvCell(v) {
  const s = (v ?? '').toString()
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}

/**
 * 音标清理：源数据里有约 5% 的条目开头带一个多余的逗号（`,ækə'dɛmɪk`），
 * 直接显示会很难看。掐掉首尾的逗号和空白。
 */
function cleanIpa(raw) {
  return (raw || '').replace(/^[,\s]+/, '').replace(/[,\s]+$/, '')
}

/** 源 JSON 的一条 → App 的一行。返回 null 表示这条不要 */
function fromSourceEntry(entry, opts) {
  const word = (entry.word || '').trim()
  if (!word) return null

  const trans = (entry.translations || []).filter((t) => (t.translation || '').trim())
  const translation = trans.map((t) => t.translation.trim()).join('；')
  if (!translation) return null

  const pos = [...new Set(
    trans.map((t) => POS_MAP[(t.type || '').trim().toLowerCase()]).filter(Boolean)
  )].join('、')

  const first = (entry.sentences || [])[0]
  const example = opts.example && first && first.sentence ? first.sentence.trim() : ''

  return { word, ipa: cleanIpa(entry.us || entry.uk), translation, example, pos }
}

/** 极简 CSV 切行（处理引号包裹和字段里的换行） */
function splitCsvRows(text) {
  const rows = []
  let row = []
  let field = ''
  let inQuotes = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (inQuotes) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++ }
      else if (c === '"') inQuotes = false
      else field += c
    } else if (c === '"') inQuotes = true
    else if (c === ',') { row.push(field); field = '' }
    else if (c === '\n') { row.push(field); field = ''; rows.push(row); row = [] }
    else if (c !== '\r') field += c
  }
  if (field || row.length) { row.push(field); rows.push(row) }
  return rows
}

/** 本地表格 → 行。JSONL 按源格式，CSV 按列名认 */
function fromLocalFile(path, opts) {
  const text = readFileSync(path, 'utf8')
  const trimmed = text.trim()
  const out = []

  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    // JSONL（一行一个对象）或整份 JSON 数组
    let entries
    if (trimmed.startsWith('[')) entries = JSON.parse(trimmed)
    else entries = trimmed.split('\n').filter(Boolean).map((l) => JSON.parse(l))
    for (const e of entries) {
      const row = fromSourceEntry(e, opts)
      if (row) out.push(row)
    }
    return out
  }

  // CSV：第一行必须是列名，认 word / ipa / translation / example / unit / pos
  const rows = splitCsvRows(text)
  if (!rows.length) return []
  const header = rows[0].map((h) => h.trim().toLowerCase())
  const col = (name) => header.indexOf(name)
  if (col('word') < 0 || col('translation') < 0) {
    throw new Error('CSV 第一行要有列名，至少得包含 word 和 translation')
  }
  for (const r of rows.slice(1)) {
    const word = (r[col('word')] || '').trim()
    const translation = (r[col('translation')] || '').trim()
    if (!word || !translation) continue
    out.push({
      word,
      translation,
      ipa: col('ipa') >= 0 ? (r[col('ipa')] || '').trim() : '',
      example: opts.example && col('example') >= 0 ? (r[col('example')] || '').trim() : '',
      pos: col('pos') >= 0 ? (r[col('pos')] || '').trim() : '',
      // 表里本来就有单元就留着，别被 --units 覆盖
      unit: col('unit') >= 0 ? (r[col('unit')] || '').trim() : '',
    })
  }
  return out
}

// ---------------------------------------------------------------- 主流程

async function main() {
  const args = parseArgs(process.argv.slice(2))

  if (args.help || (!args.list && !args.book && !args.file)) {
    console.log(USAGE)
    process.exit(args.help ? 0 : 2)
  }

  if (args.list) {
    console.log('可用的词书（--book 的取值）：')
    for (const b of BOOKS) console.log('  ' + b)
    console.log('\n也可以 --file 转换自己的表格（.jsonl / .json / .csv）。')
    return
  }

  let rows
  let name
  if (args.file) {
    if (!existsSync(args.file)) {
      console.error(`找不到文件：${args.file}`)
      process.exit(1)
    }
    console.error(`读本地文件 ${args.file}`)
    name = basename(args.file).replace(/\.[^.]+$/, '')
    rows = fromLocalFile(args.file, args)
  } else {
    const url = SOURCE_BASE + encodeURIComponent(args.book) + '.jsonl'
    console.error(`下载「${args.book}」词表…`)
    const text = await download(url, args.book)
    name = args.book
    rows = []
    for (const line of text.split('\n')) {
      if (!line.trim()) continue
      let entry
      try { entry = JSON.parse(line) } catch { continue }
      const row = fromSourceEntry(entry, args)
      if (row) rows.push(row)
    }
  }

  // 去重（同一个词头只留第一次出现的）
  const seen = new Set()
  const deduped = []
  for (const r of rows) {
    const key = r.word.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    deduped.push(r)
  }

  const final = args.limit > 0 ? deduped.slice(0, args.limit) : deduped
  if (!final.length) {
    console.error('没解析出任何单词，检查一下源文件和参数。')
    process.exit(1)
  }

  // 单元：源表格自带单元就沿用，否则按 --units 每 N 个词切一块
  const unitOf = args.units > 0
    ? (i) => `Unit ${Math.floor(i / args.units) + 1}`
    : () => ''

  const header = 'word,ipa,translation,example,unit,pos,audio'
  const body = final.map((r, i) =>
    [r.word, r.ipa, r.translation, r.example, r.unit || unitOf(i), r.pos, ''].map(csvCell).join(',')
  )
  const csv = header + '\n' + body.join('\n') + '\n'

  const out = args.out || `${name}.csv`
  writeFileSync(out, csv, 'utf8')

  const withIpa = final.filter((r) => r.ipa).length
  const withExample = final.filter((r) => r.example).length
  const units = [...new Set(final.map((r, i) => r.unit || unitOf(i)).filter(Boolean))]
  console.error(`\n写出 ${out}`)
  console.error(`  ${final.length} 个词（原始 ${rows.length} 条，去重掉 ${rows.length - final.length} 条）`)
  console.error(`  有音标 ${withIpa} 个，有例句 ${withExample} 个`)
  if (units.length) console.error(`  单元 ${units.length} 个`)
  console.error('\n把这个 CSV 传到手机上，在 App 里「单词 → 导入」选中它。')
}

main().catch((e) => {
  console.error('\n出错了：' + e.message)
  process.exit(1)
})
