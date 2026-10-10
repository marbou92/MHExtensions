/* MHExtensions site — shared behaviour (zero dependencies) */

const MH_REPO_URL = 'https://raw.githubusercontent.com/marbou92/MHRepo/main/repo.json'

/* ---------- Catalog (single source of truth: docs/index.json) ---------- */

// Each page tells app.js where the catalog lives via <script data-catalog="…">
// (pages live at different depths: /, /extensions/, /guide/, /add-repo/).
const MH_CATALOG_URL =
  (document.currentScript && document.currentScript.getAttribute('data-catalog')) || 'index.json'

async function fetchCatalog() {
  const res = await fetch(MH_CATALOG_URL, { headers: { Accept: 'application/json' } })
  if (!res.ok) throw new Error(`index.json ${res.status}`)
  return res.json()
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (ch) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }[ch]))
}

function langLabel(ext) {
  if (ext.langs.length > 1) return `Multi-language (${ext.langs.length})`
  if (ext.langDir === 'all') return 'All languages'
  return ext.langs[0] === 'en' ? 'English' : ext.langs[0]
}

function langGroup(ext) {
  if (ext.langs.length > 1) return 'multi'
  return ext.langDir === 'all' ? 'all-lang' : ext.langs[0] || 'all-lang'
}

/* Trusted repo-authored HTML (only <code>) — never user input */
function extDescription(ext) {
  return (
    ext.description ||
    esc(ext.note) ||
    (ext.langDir === 'all' ? 'All-languages source.' : 'English source.')
  )
}

function cardHtml(ext) {
  const icon = ext.icon
    ? `<img src="${esc(ext.icon)}" alt="${esc(ext.name)} icon" width="44" height="44" loading="lazy">`
    : `<img src="" alt="" width="44" height="44" style="visibility:hidden">`
  const site = ext.baseUrl
    ? `<a class="ext-site" href="${esc(ext.baseUrl)}" target="_blank" rel="noopener noreferrer">${esc(ext.baseUrl.replace(/^https?:\/\//, ''))}</a>`
    : `<span class="ext-site"></span>`
  return `
    <article class="ext-card">
      <div class="ext-head">
        ${icon}
        <div class="ext-title">
          <div class="ext-name">${esc(ext.name)}</div>
          <span class="ext-ver">v${esc(ext.version)}</span>
        </div>
      </div>
      <div class="ext-pkg">${esc(ext.pkg)}</div>
      <div class="ext-langs"><span class="lang-chip">${esc(langLabel(ext))}</span></div>
      ${ext.note ? `<p class="ext-note">${esc(ext.note)}</p>` : ''}
      <div class="ext-foot">
        ${site}
        <a class="btn btn-brand btn-small" href="${esc(ext.apkUrl)}" download>Download APK</a>
      </div>
    </article>`
}

/* ---------- Copy buttons ---------- */

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch (err) {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
    } catch (err2) {
      /* ignore */
    }
    document.body.removeChild(ta)
    return true
  }
}

document.querySelectorAll('[data-copy-target], [data-copy]').forEach((btn) => {
  btn.addEventListener('click', async () => {
    const target = btn.getAttribute('data-copy-target')
    const text = target ? (document.querySelector(target)?.textContent || '').trim() : btn.getAttribute('data-copy')
    await copyText(text)
    const original = btn.textContent
    btn.textContent = 'Copied ✓'
    btn.classList.add('copied')
    setTimeout(() => {
      btn.textContent = original
      btn.classList.remove('copied')
    }, 1600)
  })
})

/* ---------- Homepage showcase ---------- */

const homeGrid = document.getElementById('home-ext-grid')
if (homeGrid) {
  const blurb = document.getElementById('ext-blurb')
  const homeError = document.getElementById('home-ext-error')

  fetchCatalog()
    .then((data) => {
      const extensions = data.extensions || []
      if (!extensions.length) throw new Error('catalog is empty')

      if (blurb) {
        const names = extensions.slice(0, 3).map((e) => esc(e.name))
        blurb.innerHTML =
          `${extensions.length} manga sources served through MHRepo — ` +
          `${names.join(', ')} and more.`
      }

      homeGrid.innerHTML = extensions.map(cardHtml).join('')
      const homeCount = document.getElementById('home-ext-count')
      if (homeCount) homeCount.textContent = `${extensions.length} extensions`
    })
    .catch(() => {
      if (blurb) blurb.textContent = 'Manga sources served through MHRepo.'
      if (homeError) homeError.style.display = 'block'
      homeGrid.innerHTML = ''
    })
}

/* ---------- Extension browser (/extensions/) ---------- */

const browserRoot = document.getElementById('ext-browser')
if (browserRoot) {
  const state = { q: '', lang: 'all' }
  let extensions = []
  let meta = null

  const grid = browserRoot.querySelector('#ext-grid')
  const count = browserRoot.querySelector('#ext-count')
  const search = browserRoot.querySelector('#ext-search')
  const errorBox = browserRoot.querySelector('.load-error')

  browserRoot.querySelectorAll('.chip[data-lang]').forEach((chip) => {
    chip.addEventListener('click', () => {
      state.lang = chip.getAttribute('data-lang')
      browserRoot.querySelectorAll('.chip[data-lang]').forEach((c) => c.classList.toggle('active', c === chip))
      render()
    })
  })

  if (search) {
    search.addEventListener('input', () => {
      state.q = search.value.trim().toLowerCase()
      render()
    })
  }

  function render() {
    const filtered = extensions.filter((ext) => {
      if (state.lang !== 'all' && langGroup(ext) !== state.lang) return false
      if (!state.q) return true
      const hay = `${ext.name} ${ext.pkg} ${ext.baseUrl} ${ext.note}`.toLowerCase()
      return hay.includes(state.q)
    })
    count.textContent = `${filtered.length} of ${extensions.length} extensions`
    grid.innerHTML = filtered.map(cardHtml).join('')
  }

  fetchCatalog()
    .then((data) => {
      extensions = data.extensions || []
      meta = data.meta || null
      render()
    })
    .catch(() => {
      errorBox.style.display = 'block'
      grid.innerHTML = ''
      count.textContent = ''
    })
}

/* ---------- Guide: extension notes from the catalog (/guide/) ---------- */

const extNotes = document.getElementById('ext-notes')
if (extNotes) {
  fetchCatalog()
    .then((data) => {
      const items = (data.extensions || []).map(
        (ext) => `<li><strong>${esc(ext.name)}</strong> — ${extDescription(ext)}</li>`
      )
      extNotes.innerHTML = items.join('')
    })
    .catch(() => {
      extNotes.innerHTML =
        '<li><strong>Catalog unavailable</strong> — browse the ' +
        '<a href="../extensions/">extensions page</a> instead.</li>'
    })
}
