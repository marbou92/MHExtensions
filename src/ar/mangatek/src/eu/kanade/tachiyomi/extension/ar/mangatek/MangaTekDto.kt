package eu.kanade.tachiyomi.extension.ar.mangatek

// v103: emptied. These wire shapes (SearchResponseDto, SeriesDto, ChapterDto,
// PageDto, ...) belonged to the previous rewrite that assumed the Axis
// platform API — a shape that only ever existed on the mangatuk.com twin, not
// on mangatek.com (an Astro SSR app). The adopted upstream implementation uses
// Dto.kt instead, so every class here became dead code and was removed to keep
// the module clash-free. Kept as a near-empty file on purpose: changed-files
// deliveries cannot ship deletions, so this same-name overwrite heals existing
// checkouts. (ktlint's no-empty-file rule needs a real declaration.)

internal const val MANGATEK_V103_DTO_STUB = "emptied in v103"
