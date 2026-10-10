/* MHExtensions site — shared behaviour (zero dependencies) */

const MH_REPO_URL = 'https://raw.githubusercontent.com/marbou92/MHRepo/main/repo.json'

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

/* ---------- Extension browser (extensions.html) ---------- */

const browserRoot = document.getElementById('ext-browser')
if (browserRoot) {
  const state = { q: '', lang: 'all' }
  let extensions = []
  let meta = null

  const grid = browserRoot.querySelector('#ext-grid')
  const count = browserRoot.querySelector('#ext-count')
  const search = browserRoot.querySelector('#ext-search')
  const errorBox = browserRoot.querySelector('.load-error')

  const LANG_FILTERS = [
    { id: 'all', label: 'All' },
    { id: 'multi', label: 'Multi-language' },
    { id: 'all-lang', label: 'All languages' },
    { id: 'en', label: 'English' },
  ]

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

  function langLabel(ext) {
    if (ext.langs.length > 1) return `Multi-language (${ext.langs.length})`
    if (ext.langDir === 'all') return 'All languages'
    return ext.langs[0] === 'en' ? 'English' : ext.langs[0]
  }

  function langGroup(ext) {
    if (ext.langs.length > 1) return 'multi'
    return ext.langDir === 'all' ? 'all-lang' : ext.langs[0] || 'all-lang'
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

  fetch('../index.json')
    .then((res) => {
      if (!res.ok) throw new Error(`index.json ${res.status}`)
      return res.json()
    })
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
