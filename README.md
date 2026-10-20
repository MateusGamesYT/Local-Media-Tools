<img src="docs/icon.png" width="96" align="right" alt="">

# Local Media Tools (Android)

A private, fully on-device media toolbox: a **Gallery** that finds people and things in your
photos and lets you search them in plain words, **highlight videos** cut to music the phone writes
itself (or to your own song), **printing** to Wi-Fi printers without the printer maker's app, and **34
tools** for photos, video, GIF, documents, PDF and audio, among them a **file reader** for the PDF,
Word, Excel and PowerPoint files Android can't open by itself, and on-device AI for **face blurring in photos and
videos**, **background removal**, **one-tap enhancement**, a **magic eraser**, a **duplicate finder** and
a **denoiser** that keeps RAW files RAW. Nothing is uploaded: the app never connects to servers
on the internet (its only network traffic is to your own printer, and the code allows nothing else),
originals are never modified, and every result is written to a hidden file that is only published
after it has been completely written and checked.

**Tool stacks** chain any of them: pick files once, add steps (for example Blur faces → Video
compressor → Video → GIF → GIF optimizer) and run them all with one tap.

**Install:** [`release/LocalMediaTools-1.11.0.apk`](release/LocalMediaTools-1.11.0.apk)
(Android 10 or newer, arm64 / armv7; allow "install unknown apps" for your file manager or browser).
It installs over earlier versions (same signing key).

| Gallery | Search | Faces with names | People | Things | A person | All faces |
|---|---|---|---|---|---|---|
| ![](docs/screenshots/gallery-photos.png) | ![](docs/screenshots/gallery-search.png) | ![](docs/screenshots/gallery-viewer.png) | ![](docs/screenshots/gallery-people.png) | ![](docs/screenshots/gallery-things.png) | ![](docs/screenshots/gallery-person.png) | ![](docs/screenshots/gallery-faces.png) |

Gallery screenshots show Creative Commons photos (CC BY 2.0) of Caroline Wozniacki, Ian Somerhalder and
Kelly Clarkson by Andrew Campbell, karlnorling, cirodelia47, Jean & Nathalie and vagueonthehow (sources
in [`CREDITS.tsv`](app/src/test/resources/people/CREDITS.tsv)), as the app found and grouped them.

| Highlight video | Duplicate finder | One group at a time | Name people |
|---|---|---|---|
| ![](docs/screenshots/highlight.png) | ![](docs/screenshots/duplicates.png) | ![](docs/screenshots/duplicates-review.png) | ![](docs/screenshots/name-people.png) |

| File reader | A Word document | A spreadsheet | Slides | A PDF, searched |
|---|---|---|---|---|
| ![](docs/screenshots/reader.png) | ![](docs/screenshots/reader-word.png) | ![](docs/screenshots/reader-sheet.png) | ![](docs/screenshots/reader-slides.png) | ![](docs/screenshots/reader-pdf.png) |

The reader screenshots show a NASA report, a USDA table and an EPA project summary (public domain) and an Apache POI sample presentation (Apache-2.0); credits in [`CREDITS.md`](app/src/test/resources/docs/CREDITS.md).

| Print | Choosing the printer |
|---|---|
| ![](docs/screenshots/print.png) | ![](docs/screenshots/print-printers.png) |

| Tool stack | Setting up a step | Choosing the next tool |
|---|---|---|
| ![](docs/screenshots/tool-stack.png) | ![](docs/screenshots/stack-step.png) | ![](docs/screenshots/stack-picker.png) |

| Home | Denoiser | Blur faces | Background remover | Auto enhance | Speed & timelapse |
|---|---|---|---|---|---|
| ![](docs/screenshots/home.png) | ![](docs/screenshots/denoiser.png) | ![](docs/screenshots/face-blur.png) | ![](docs/screenshots/background-remover.png) | ![](docs/screenshots/auto-enhance.png) | ![](docs/screenshots/speed.png) |

The Denoiser's preview shows a real noisy phone photo from SIDD (MIT licence; [`CREDITS.tsv`](app/src/test/resources/denoise/CREDITS.tsv)) and what the app's photo denoiser made of it.

| Português (Brasil) | 简体中文 | 日本語 | Русский |
|---|---|---|---|
| ![](docs/screenshots/home-pt-BR.png) | ![](docs/screenshots/home-zh-Hans.png) | ![](docs/screenshots/home-ja.png) | ![](docs/screenshots/home-ru.png) |

| Magic eraser | Looks | Crop & straighten | Settings |
|---|---|---|---|
| ![](docs/screenshots/editor-erase.png) | ![](docs/screenshots/editor-looks.png) | ![](docs/screenshots/editor-crop.png) | ![](docs/screenshots/settings.png) |

## What's new in 1.11.0: a file reader for documents Android can't open

- **File reader** is a new tool in *Documents & PDF*. It opens **PDF, Word (DOCX, DOC), Excel (XLSX,
  XLS), PowerPoint (PPTX, PPT), OpenDocument (ODT, ODS, ODP), RTF, EPUB e-books, CSV/TSV, Markdown,
  HTML, JSON, XML and text files**. Open them with *Open a file*, or from another app (Files, e-mail,
  messengers) with **Open with → File reader (Local Media Tools)**. What a file is comes from its
  contents, not its name, so a renamed attachment still opens. Files are only read: nothing is changed,
  and nothing is uploaded.
- **Text documents** (Word, OpenDocument text, RTF, e-books, HTML, Markdown, text) reflow on a white
  page that fits the phone. They keep:
  - headings, bold, italic, underline, colours, sizes and highlighting;
  - lists with their numbers and bullets;
  - tables, including merged cells (wide ones scroll sideways);
  - pictures, footnotes and endnotes (gathered under *Notes* at the end), and text in text boxes and
    shapes.

  Pinch (or *Zoom in*/*Zoom out* in the menu) changes the text size, and text can be selected and
  copied. *Contents* lists the headings or the book's chapters.
- **Spreadsheets** (XLSX, XLS, ODS, CSV) show as a grid with column letters and row numbers, like on
  a computer. They keep:
  - every number format as Excel shows it: dates and times, currencies, percentages, fractions,
    scientific notation, colours and conditions, with the separators of the app's language;
  - fonts, fills, borders and alignment, merged cells, frozen rows and columns, and hidden rows,
    columns and sheets;
  - text running over into empty neighbouring cells.

  Tapping a cell shows its content or its formula in the bar above (old .xls files too: the app spells
  their formulas back out of Excel's binary format, `=SUM(B2:B6)`, sheet references and array
  formulas included). Tabs at the bottom switch sheets. Formulas aren't recalculated: cells show the
  values the file was saved with, as Excel does when it opens a file.
- **Slides** (PPTX, PPT, ODP) are drawn: backgrounds, shapes with their fills and outlines, cropped
  pictures, tables, and text with bullets, alignment and the presentation's shrink-to-fit. Old .ppt
  files also get their colour scheme, the master's logos and lines, and grouped shapes in their right
  place. *Speaker notes* are in the menu, hidden slides are marked, and *Slides* lists every slide by
  its title.
- **PDF pages** are drawn by Android's own PDF engine, the same as the system viewer. Zoom goes to
  6×, and the part on screen is drawn again sharply once you stop moving. Search marks every match on
  the pages, and *Contents* shows the PDF's bookmarks. A **password-protected PDF** asks for its
  password; the unlocked copy stays in the app's private cache only while it's shown. *Copy this
  page's text* and *Print* are in the menu.
- **Search** works in every kind of file: "3 of 27", next and previous, every match marked. Large PDFs
  are searched page by page, showing how far it has got.
- **Links** in documents are never opened by surprise. A tap shows the address first, with *Copy* and
  *Open*; *Open* hands it to your browser. The app itself still never connects to the internet.
- **Nothing is left out silently.** What a reader can't show is said in a note on top and in *File
  information*: charts and diagrams (drawn as labelled boxes), pictures in old metafile formats (WMF,
  EMF), embedded objects, linked pictures, chart sheets.
- **Recent files** are listed on the reader's first screen:
  - a file picked in the reader is read again from where it is (Android keeps the permission);
  - a file another app only lent ("Open with") is kept as a private copy, at most 12 files and
    300 MB, removed together with its entry; *Clear the list* removes them all.
- **Damaged and hostile files stop with a message, never a crash.**
  - Every size and count is checked: at most 256 MB per part and 1 GB in all, so a small file that
    unpacks to gigabytes stops; cells hold at most 32,767 characters, as in Excel.
  - Deeply nested files are read on a thread with a large stack.
  - A cut-off ZIP (a broken download) is rebuilt from its parts' own headers, as Office and
    LibreOffice do.
  - Password-protected Office files, Word 6/95 files and Excel binary workbooks (.xlsb) say exactly
    that.
- **Measured on 1,972 real files**: Apache POI's test files (every Office version, many made to break
  readers) and thread 0 of govdocs1 (documents from U.S. government web servers). The scripts are in
  [`buildtools/docs/`](buildtools/docs/README.md).
  - **1,807 of 1,911** Office, OpenDocument, RTF, HTML, CSV, XML and text files opened, including
    **540 of the 542** government files. Of the 104 that didn't: 51 are fuzzer test files, 19 are
    password-protected, 14 are Word 6/95, 11 are .xlsb, 2 come from even older versions, and 7 are
    damaged.
  - **Text against Apache Tika** (median share of Tika's words the app shows / of the app's words
    Tika also has): Word 0.991 / 1.000 (.doc) and 0.989 / 1.000 (.docx); Excel 1.000 / 1.000;
    PowerPoint 0.945 / 1.000 (.ppt) and 0.874 / 1.000 (.pptx); HTML 0.992 / 0.991; text and CSV
    1.000. Where the app shows less, it is mostly text Tika also pulls from places the page doesn't
    show: embedded objects, chart data, notes and handout masters, document properties, picture names.
  - **Every spreadsheet cell against LibreOffice** (cells as shown): of 1,837,717 cells in 453 .xls
    files, 99.13 % read exactly the same, and 99.52 % leaving out formula cells that LibreOffice
    recalculates (TODAY() and the like). Of 1,050,233 cells in 348 .xlsx files: 99.86 % and 99.90 %.
    This check found seven bugs, all fixed, among them rounding (30.395 now shows as 30.40, as in
    Excel), system date codes shown twice, and charts' cached data taken for cells.
  - **The newer formats on real content**: 45 government documents of each kind re-saved by
    LibreOffice as DOCX, ODT, XLSX, ODS, PPTX and ODP. All 270 open; median recall against Tika's
    reading of the original is 0.997 for DOCX and ODT, 1.000 for XLSX and ODS, 0.983 for PPTX and
    0.960 for ODP.
  - **Pictures**: 155 in 28 .doc files, 808 in 90 .ppt files, and 206 of 274 in .docx and .pptx files
    are shown. 583 pictures in old metafile formats in 72 .ppt files, and those in 55 .doc files, are
    named in the notes instead.
- **Tested** on the JVM and in the Android test runtime with real documents: a NASA report, a USDA
  table and an EPA project summary (public domain), in their original Office 97–2003 files and
  re-saved in every newer format, plus Apache POI's samples made by Microsoft Office; credits in
  [`CREDITS.md`](app/src/test/resources/docs/CREDITS.md).
  - The same report reads alike (within 1 % of its 3,389 words) in .doc, .docx, .odt, .rtf and .epub,
    with its two figures in each.
  - The same table gives identical cells in .xls, .xlsx, .ods and .csv, and identical formulas in the
    first three.
  - The UI tests open each kind of file from the tool, from the file picker and through "Open with",
    and also cover search, the formula bar, slides, PDF pages and search (with PDFBox, since the test
    runtime has no Android PDF engine), recent files, and locked and unknown files.
  - Not tried on a phone.
- **Limits**: metafile pictures (WMF, EMF), charts, SmartArt and equations aren't drawn; Word 6/95 and
  .xlsb files can't be opened yet. Fonts are the phone's, so lines can break a little differently than
  on a computer.
- **Translated** into all ten languages.

## What was new in 1.10.3: RAW photos are files like any other

This replaces the RAW rules of 1.10.1 and 1.10.2.

- **Your choice on screen comes first.** Where you choose the format, a RAW photo (DNG, CR2, NEF,
  ARW…) gets that choice like every other file, with no exception and no warning. That covers Convert
  images, the Image compressor, the Lossless image optimizer (PNG or WebP), the photo editor's save
  sheet, a PDF's pages and the background remover's background. The compressor's and converter's
  quality settings apply to RAW photos too.
- **Tools that keep each file's format keep RAW files RAW where they can.** The Denoiser writes DNG,
  CR2, NEF and ARW back as themselves.
- **Where a tool can't keep a RAW photo RAW, you're told first, and it follows the other files.** This
  applies to Auto enhance, Blur faces, Bulk watermark, Remove metadata, and RAW types the Denoiser
  can't write back. Before, the Denoiser left those out; now it cleans them as pictures.
  - Among PNGs, the RAW photo becomes a lossless PNG. Otherwise it becomes a JPEG at full quality,
    which includes when there are no other files to follow or as many of each.
  - The warning says which, for example "1 RAW file (CR2) can't be saved in its own format by Auto
    enhance, so it will be saved as a JPEG at full quality", with Continue or Cancel. It also appears
    in tool stacks. Originals are never changed.
- **Full quality, measured.** PNG is lossless. A JPEG can't be bit-exact, because colour conversion and
  the image transform round, so a RAW photo's JPEG uses the highest setting the format has: every
  quantisation step 1 and full colour resolution, written by the app's own encoder. On 87 real photos
  that came to 55.3 dB from the original. The largest difference was 3 levels of 255, and 96 % of
  pixels were within 1 level. Android's own encoder at the same quality halves the colour resolution
  (51.1 dB, differences up to 28), so every quality-100 JPEG the app makes now goes through its own
  encoder. Tools whose job is compression keep the quality you set.
- **Tested on a piece of a real Samsung Galaxy S9 DNG** under the Android test runtime:
  - Auto enhance next to a JPEG gives a full-quality JPEG, while the other photo keeps the tool's
    usual quality.
  - Remove metadata next to PNGs gives a PNG with exactly the decoded pixels, upright. On its own it
    gives a full-quality JPEG with no metadata segment, at most 4 levels from the picture.
  - Convert images, the compressor and the optimizer give the formats chosen.
  - The watermark follows the other files.
  - Not tried on a phone.
- **Translated** into all ten languages.

## What was new in 1.10.2: RAW photos become lossless PNGs, and Remove metadata cleans them

- **Lossless PNG for RAW photos.** A tool that isn't made for converting can't save its result of a
  RAW photo (DNG, CR2, NEF, ARW…) as RAW. It now saves it as a lossless PNG instead of a JPEG, so
  nothing of the picture is lost to compression. This covers Auto enhance, Blur faces, Bulk watermark,
  Remove metadata, the Lossless image optimizer (even when set to WebP) and the Background remover
  (also on a plain colour). The photo editor offers PNG first for a RAW photo; you can still pick
  another format. Convert images and the Image compressor keep the format you choose, since
  converting is what they are for.
- **Remove metadata now takes RAW files.** It can't clean a RAW file in its own format, so it makes a
  clean PNG of the picture alone, upright, with none of the file's metadata (location, camera, dates
  and the rest). Before, it left RAW files out.
- **The warning says what happens:** "1 RAW file (CR2) will be saved as a lossless PNG by Auto
  enhance: it can't be saved as CR2", with Continue or Cancel, now for Remove metadata too, and in
  tool stacks. Originals are never changed.
- **Mind the size:** on the app's 24 real test photos a PNG took 0.5–2 bytes per pixel (median 1.1).
  For a 24 MP photo that is about 13–49 MB, often as much as the RAW file or more.
- **Tested on a real phone RAW file** (a piece of a Samsung Galaxy S9 DNG, under the Android test
  runtime):
  Remove metadata's PNG has exactly the pixels Android decodes from the DNG, turned upright as the
  file says, with no metadata in it. Auto enhance, Bulk watermark and the optimizer set to WebP all
  save PNGs. Not tried on a phone.
- **Translated** into all ten languages.

## What was new in 1.10.1: RAW files in every tool, and a duplicate finder that shows how far it is

- **RAW files work in every tool again.** 1.10.0 left RAW photos (DNG, CR2, NEF, ARW…) out of tools
  that would save them in another format. Now every tool takes them.
  - **A warning first:** the four tools that save an edited copy of each photo (Auto enhance, Blur
    faces, Lossless image optimizer, Bulk watermark) say so before starting, for example "1 RAW file
    (CR2) will become another file type with Auto enhance: it can't be saved as CR2", with Continue
    or Cancel. A tool stack says it for the first step that would change them, and the photo editor's
    save sheet says it for a RAW photo. Originals are never changed.
  - **No warning where changing the type is the point:** Convert images, the Image compressor and the
    tools that make something new (PDFs, merges, cut-outs, prints, highlight videos). The Denoiser
    still keeps RAW files RAW. Remove metadata still leaves them out and says why: it can't clean them
    without re-encoding.
- **The duplicate finder shows how far it is.**
  - **The bar was there, but black:** since the first version, a mistake in the progress bar drew its
    filled part black on a dark grey track. That is most likely why the finder looked stuck. Every
    progress bar in the app had it (exports, the Gallery, highlight videos); they now fill in the
    app's violet. A test now checks the bar's colour.
  - **One bar for the whole scan:** looking at the photos fills the first 80 %, comparing them the
    rest. It no longer starts over between steps, and it moves smoothly: it used to be rebuilt in
    steps of 32 photos.
  - **Numbers:** "1500 of 6000 photos · 20 % · about 4 minutes left". The time left counts only the
    photos not measured in an earlier scan (those take no time), and settles instead of jumping.
  - It says you can use the rest of the app meanwhile; the scan keeps going.
- **Translated** into all ten languages.
- **Not tried on a phone** (there is none here). The tests cover the warning dialog, the stack and
  tool rules, the progress screen and its colour, and the real scan's progress on real photos (only
  ever moving forward). How long the time left takes to appear, and how close it is, depends on the
  phone and the library.

## What was new in 1.10.0: a denoiser that keeps RAW files RAW, your own song, people and things in videos

- **New tool: Denoiser** (Edit & AI). It cleans the grain from photos, camera RAW files and videos,
  a whole batch at a time.
  - **Strength from the file itself:** each file's own noise is measured and only that much is
    removed. Light, Normal and Strong adjust it.
  - **Preview:** a piece of the first photo is shown before and after, at 100 %.
  - **Photos** keep their format (JPEG stays JPEG, PNG PNG, WebP WebP). HEIC and others Android
    can't write become JPEG.
  - **Videos** are cleaned on the phone's graphics chip, frame by frame together with the frames
    before them, and saved as MP4 at their original quality.
  - **Measured, not guessed:** on real phone noise (SIDD) the photo setting takes scenes it was
    not tuned on from 27.7 to 33.8 dB. RAW files at real high-ISO noise levels go from 42.1 to
    49.5 dB, videos from 28.3 to 34.3 dB. Details in `buildtools/denoise/README.md`.
- **Detailed photos are left alone.** Measuring showed that a photo full of fine detail (grass,
  branches, bark) can look like a noisy one to a noise meter. A straightforward denoiser would have
  smeared those photos. The Denoiser recognises fine detail by how unevenly it is spread and holds
  back. On clean photos full of detail the change stays invisible (33 dB or more), while noisy photos
  are still cleaned.
- **RAW files stay RAW** — in the Denoiser, a `.cr2` comes out as a `.cr2`:
  - **Kept as they were:** previews, maker notes and all metadata are kept byte for byte, and only the
    sensor data is cleaned and written back in the camera's own compression. That covers Canon CR2,
    DNG (phones and cameras), Nikon NEF and Sony ARW, checked against LibRaw and ExifTool on 18
    cameras' files.
  - **Left out, with a reason:** RAW variants the app can't write back exactly (CR3, RAF, ORF, RW2,
    Canon sRAW, lossy NEF) are left untouched, and the message says why.
  - **Other tools:** tools that would turn a RAW photo into another format (auto enhance, watermark,
    blur…) now leave RAW files out and say so. In a tool stack, the RAW file passes those steps
    unchanged. Only the format tools change a RAW file's format: Convert images and the Image
    compressor. Tools that make something new from photos (PDF, merges, prints, highlight videos)
    still take them.
  - **Recognised by content:** a DNG is now recognised as RAW even when its name doesn't end in
    `.dng`, and Canon CR3 files no longer pass for videos.
- **Your own song in the highlight video.**
  - **Choosing it:** under Music, pick "My song" and choose any audio file on the phone.
  - **Fitting:** the app finds the song's beat, so the cuts fall on it. It starts on the first beat
    after any silence, repeats a short song on whole bars without repeating its ending, and plays it
    at the level of the app's own music, quieter while your clips speak.
  - **Accuracy:** on 200 real songs from the Free Music Archive not used to tune it, it found the same
    tempo as two independent references (librosa and Essentia) in 80 % of the songs where those
    agree, and double or half in 10 %. Songs without a steady beat keep the video's own rhythm.
    "Listen" plays your song as the video will.
- **The Gallery finds people and things in videos far better.**
  - **More frames:** a video used to be looked at in only three frames, which on many videos were all
    the same keyframe. Now its frames are spread over the whole video, one every 3 seconds (3 to 16),
    each a different picture.
  - **Measured on real videos of people** (Global Lives Project, CC BY): the people seen clearly in each video are now found in 86 % of the cases (24 of 28; before: 43 %), and things such as dogs, cars or beaches in 82 % (before: 74 %), with hardly any wrong tags.
  - **Retried, not emptied:** a video that can't be decoded is tried again later instead of being
    recorded as empty.
  - **What you said stays:** existing videos are looked at again once after updating, and what you
    said about the faces in them (names, "not this person", hidden faces) is kept.
- **Translated**: every new text is in all ten languages, including the RAW messages and the
  Denoiser's results.
- **Not tried on a phone** (there is none here): the video denoiser on the graphics chip, decoding
  your song, and the Gallery reading video frames. The arithmetic of all three is the same code the
  measurements and tests above ran; what only a phone does (its video decoders and graphics chip) is
  untested. On a phone the Denoiser takes from several seconds to a minute per photo, depending on its
  size; RAW files and videos take longer.

## What was new in 1.9.1: duplicate finder fixes

- **Copies of turned photos are found again.** The finder compared each photo's shape using the size
  and orientation the phone's photo library reports, which some phones get the wrong way round for
  photos taken upright; a copy then never matched its original. The shape now comes from the photo's
  own upright thumbnail (the library's size is still used to pick the sharpest, largest copy).
- **No more silent "Nice and tidy!".** A photo whose picture can't be read used to be dropped without
  a word, so a phone where reading failed reported no duplicates at all. Such photos are now counted
  and shown, still checked for identical copies (by their bytes), and if none can be read the scan
  says so and why.
- **Android 14's "selected photos" access is explained**: when the app may see only some photos, the
  finder says only those were checked and offers to allow all photos.
- The first scan after updating looks at every photo again (the saved measurements gained the
  thumbnail's shape).
- **Tested end to end now**: a new test runs the real scan (library, thumbnails, measurements, saved
  measurements, identical files, grouping, the result screen) on the real test photos, with an exact
  copy, a damaged file and a copy whose reported size is turned; before, only the grouping and the
  screens were tested, each on its own. Not tried on a phone: if the finder still misbehaves on yours,
  tell me what it shows.

## What was new in 1.9.0: ten languages, a stronger face model, auto enhance that learned from experts

- **The app speaks ten languages**: English, Português (Brasil), Español, Français, Deutsch, Italiano,
  Русский, 简体中文, 日本語 and 한국어. It follows the phone's language (Portuguese from anywhere gets the
  Brazilian translation; Traditional Chinese, which has none yet, falls back to the next language on the
  phone's list, then English), or choose one in **Settings → App language**. Everything follows it:
  screens, messages, notifications, what tools write into their results (the highlight video's title,
  moment names such as "Na praia" or "午餐" and its dates, auto enhance's notes, default file names such as
  "Mesclado" or "合并"), and dates and numbers are written the language's way, with each language's
  plurals (Russian's three forms, none in Chinese, Japanese and Korean). The "Print" entry in other
  apps' share menus follows the phone's language. File-name endings such as `_enhanced` or `_cutout`
  stay the same in every language, so copies sort and match on any phone. **The translations are
  machine-made and not yet checked by native speakers**; tests check every one against the code
  (placeholders, plural forms, date patterns, search words) and open the home screen, every tool's
  screen and every tab in every language.
- **Gallery search in your language**: things, places, kinds of media, months and "last week" in
  each language ("fotos de cachorro na praia em junho de 2023", "海边的狗", "해변에서 강아지"), with accents
  optional, Chinese and Japanese typed without spaces and Korean particles understood; English words
  always work too.
- **A stronger face model for the Gallery's people**: InsightFace's ResNet-50, as TensorFlow Lite with
  8-bit weights (44 MB). On the same 885 faces of 78 real people as 1.8.0 (settings chosen on half the
  people, checked on the other half): a person's faces in their main group 92 → 96 % and 90 → 93 %,
  faces left on their own 8 → 4 % and 12.5 → 10 %, still no face of another person in anyone's group,
  and naming each person's biggest group once puts 97 % / 90 % of their faces under the name (93 % /
  89 %). Face blur keeps MobileFaceNet (fast enough for every frame of a video). After the update the
  app describes the faces again, keeping every name, confirmation and correction. Same licence as
  1.8.0's model (InsightFace, non-commercial research use). Details:
  [`buildtools/gallery/README.md`](buildtools/gallery/README.md).
- **Auto enhance learned from expert retouching.** Its settings now come from a model fitted on
  MIT-Adobe FiveK (expert C's retouches of 320 photos), through the app's own code. On 154 photos it
  never saw, the result comes much closer to the expert (colour difference ΔE00 8.89; 1.8's rules
  10.88, which was slightly *further* than leaving the photo alone, 10.82), better for every kind of
  scene (portraits 11.28 → 8.42, landscapes 10.52 → 8.32), and confirmed on 77 more photos looked at
  only once at the end (9.21; rules 11.03). Dark photos are brightened about as much as the experts did,
  and photos that are already good are changed far less (an expert's finished version by 4.89 instead
  of 7.59). White balance comes only from the colour of the light, the skin of the faces and the scene,
  as the experts did it: skin that looks cold is warmed, while scenes whose light really is cool (snow,
  water, sky) are left cool. Documents keep the 1.8 rules. Measured on a camera dataset, not on phone
  photos; details: [`buildtools/enhance/README.md`](buildtools/enhance/README.md).
- **Cleaner cut-outs in the background remover**: the model also looks at the mirrored photo and takes
  a second, closer look at the subject when it is small, and the edges are fitted over a wider area.
  On AIM-500 (500 photos with exact cut-outs; settings chosen on half, checked on the other half):
  3.8 % less error (SAD), 8.9 % by MSE, most for furniture, plants, animals and see-through things;
  portraits about the same (slightly worse, 42.8 → 43.5). It takes about three times as long per photo. Details: [`buildtools/matting/README.md`](buildtools/matting/README.md).
- **Magic eraser: measured, unchanged.** On 55 object-shaped holes in 3000 px photos, neither the size
  of the area the AI looks at nor a bigger working size made a clear difference. A stronger model
  (LaMa) fills big holes clearly better (+1.9 dB), but its available build can't run in the app and
  takes about 18 s per hole even on a desktop processor, so it is not in this version
  ([`buildtools/eraser/README.md`](buildtools/eraser/README.md)).
- **Fixes**: names and captions are shortened by whole characters (cutting an emoji in half could make
  an invalid file name or a broken highlight-video caption); a picture region reaching past the photo's
  edge no longer crashes the editor's fallback decoder; nothing in the app depends on English message
  text any more (how a refused video container or a changed printer identity is recognised).
- **The APK is stored with Git LFS** (it is now larger than GitHub's 100 MB file limit); the install
  link above still downloads it as before.
- **Not yet tried on a phone**: the new face model's speed and memory (about 90 MB while the Gallery
  is organised, measured on the build machine), and the new background remover's speed.

## What was new in 1.8.1: the Gallery keeps going when the phone is warm

- **Organising the Gallery no longer stops as soon as the phone warms up.** It used to pause at
  Android's "severe" heat level, which phones reach easily during a long scan. Now it carries on at
  about half speed there (a short rest after each photo; the strip says "slower while the phone is
  warm") and only pauses at the "critical" level, until the phone has cooled below it. Pausing for a
  low battery or Battery Saver is unchanged.

## What was new in 1.8.0: a stronger face model, so one person stays one person

- **A new face recognition model**: InsightFace's MobileFaceNet ("w600k_mbf") replaces SFace for the
  Gallery's people and for face blur. Measured through the app's own grouping code on 885 checked
  faces of 78 real people in Creative Commons photos (hats, sunglasses, stage make-up, expressions,
  profiles, small faces, strangers in the background), with the settings chosen on half of the people
  and checked on the other half:

  | | SFace (1.5–1.7) | MobileFaceNet (1.8) |
  |---|---|---|
  | A person's faces in their main group | 86 % / 86 % | **92 % / 90 %** |
  | Faces left on their own | 15 % / 18 % | **8 % / 12.5 %** |
  | Under the right name after naming each person's biggest group once | 88 % / 82 % | **93 % / 89 %** |
  | Faces of another person in someone's group | 0 / 0 | 0 / 0 |

  (First number: the half of the people the settings were chosen on; second: the other half.) With
  all 78 people at once: 3 faces with the
  wrong person (SFace 8) and 50 strangers' faces in someone's group (SFace 36, of 3,644). It is better
  for 23 of the 78 people and worse for 3. Two cautions: in the app's test set of each person's six
  hardest photos, Lady Gaga's six stage looks stay apart (SFace grouped five), so someone with only a
  few photos in very different looks can still be split; and the 78 people are public figures, whom the
  model may have seen while it was trained, so your own photos may show a smaller gain.
- **Smaller and faster**: 6.8 MB instead of 9.9 MB, and 21 ms instead of 52 ms per face on one core of
  the build machine (9 ms instead of 30 ms on four).
- **"Is this …?" suggestions** now come for 97–100 % of a person's groups and 80–86 % of their single
  faces (SFace: 94–97 % and 74–75 %), still without a wrong one in the measurement.
- **Your names are kept.** After the update the app looks again only at the photos and videos that
  have faces (the others, and every "things and places" tag, stay as they are). Each face keeps its
  place, so names, confirmations, "this isn't …" corrections and hidden faces stay with it. The
  Gallery shows "Updating face recognition · names are kept" meanwhile, and people are regrouped once
  at the end.
- **Learning from your corrections**: four ways for the app to learn more from naming were measured
  on top of the new model (confirmed faces as examples, a comparison learnt from the people you named,
  easier joining for named people, letting unclear faces join them): none gained more than about one
  point without letting strangers in, so none shipped. What the app learns from you stays what counts:
  every name, confirmation and correction anchors the grouping from then on.
- **Licence**: the model's weights are InsightFace's, for non-commercial research use only (the app is
  free and non-commercial); credited under Settings → Open-source licences. Details and all numbers:
  [`buildtools/gallery/README.md`](buildtools/gallery/README.md).
- **Tested** on real photos of real people: the app's Kotlin code gives the same face descriptions as
  the evaluation (cosine 0.99999), its new face alignment the same crops as OpenCV's, the people are
  grouped better than with SFace on the same photos, and a UI test updates a library from 1.7.0 keeping
  every name and correction. **Not yet run on a phone**: phones load the model with OpenCV 4.12 (the
  tests use 4.9 and 4.11); a self-check at start falls back to the basic face mode if a phone computes
  it wrongly.

## What was new in 1.7.0: highlight videos, a precise duplicate finder, naming people quickly

- **Highlight video** (new, in Video): pick the photos and videos of a day out, a trip or a party.
  The app puts them in the order they were taken (photo and video clocks lined up: EXIF local time
  and zone, video UTC), splits them into **moments** where there's a long pause or the scene changes,
  and names each from what's in it and the time of day ("Setting off", "Lunch", "At the beach",
  "Sunset", "Dinner", "Day 2 · Breakfast"…). It scores every shot (sharpness, exposure, faces,
  favourites; a burst counts once), gives each moment its share of the length, takes the liveliest
  stretch of each video (movement, faces, sound) and edits it all **chronologically on the music's
  beat**: cuts within a moment, cross-fades between moments, a slow zoom on photos that keeps faces in
  view, pictures of another shape shown whole over a blurred copy of themselves, the title and each
  moment's name on screen. Landscape, portrait or square (automatic: the shape most of your pictures
  have), 15–90 s or automatic.
- **Change anything before saving**: the timeline shows every moment with its shots and marks the
  ones in the edit with their length; tap a shot to always include it, again to leave it out; rename
  moments, set the title, turn captions or the videos' own sound off.
- **Background music written on the phone, always instrumental**: an open-source music model,
  Magenta's MusicVAE "trio" (Apache-2.0, Google), writes melody, bass and drums for your video; the
  app arranges it (intro, verses, chorus, outro, every note in one key, chords for a pad) and plays it
  with its own synthesized instruments — no recordings and no voices anywhere in the chain. Upbeat,
  Chill or Cinematic, **Another tune**, and **Listen** before saving. While a video clip plays its own
  sound, the music steps back to 22 % (smoothly, 250 ms down, 400 ms up) and returns after it.
- **Duplicate finder: no more unrelated photos called the same.** Every match now needs two
  independent signals (the image model *and* the picture's layout, or matching details found by a
  feature check), similar shots must be taken within 10 minutes of each other, pages of text and dark
  shots only match as true copies, and photos taken seconds apart are never treated as copies of each
  other. Measured through the app's own code on 8,466 real photos (Flickr, CC BY 2.0, including 1,905
  labelled look-alikes: screenshots, documents, night skies, clouds, snow…): **groups with unrelated
  photos 4 → 0** (1.6.0: 53 wrong pairs, the largest group 13 photos), re-saved copies still found
  (1,475 of 1,500; 1.6.0: 1,483), the same in both halves of the test set; details in
  [`buildtools/duplicates/README.md`](buildtools/duplicates/README.md).
- **Keep rules** decide what is suggested: always keep RAW photos (and the best other copy next to
  them), favourites, and photos of the people you choose from the Gallery; which copy to keep (best
  quality, the original, the newest, the smallest); optionally suggest similar shots too (the sharpest
  stays). Copies are suggested; similar shots are yours to decide.
- **Much faster to go through**: one list with each group's photos large, tap to keep or remove,
  long-press to open; **Keep all** / **Suggested** per group; sort by space, date or size of group;
  only copies or only similar shots; or **Review one by one** with swipes. The scan reads thumbnails
  on several cores.
- **Name people quickly**: the People tab's **Name people** goes through the unnamed groups biggest
  first, eight faces at a time: type a name and press Enter (an existing name adds the faces to that
  person), tap a suggestion ("This is Ana", only when the faces really match) or a recent name, Skip,
  "No one I know", or split off the faces that aren't the same person. Suggestions were measured on
  802 faces of 78 real people: the old thresholds would have suggested 39 wrong names, the new ones
  none (checked on people they weren't tuned on).
- **Lighter and faster**: the music is written in 0.6 s and played in 1.4 s for 90 s on the build
  machine (2.8× and 2.2× faster than this version's first draft) with 53 MB instead of 124 MB of
  working memory, the mix is made in the music's own buffer, and the object and scene models are
  freed as soon as a highlight video no longer needs them.
- **Tested** on real photos of real people (faces kept in frame on every frame of every shape;
  reference frames drawn from the plan), the music model against its own JavaScript implementation
  (the same choice at every one of 384 steps), the sound mix, capture times, moments and the timeline
  screen. **Not yet run on a phone**: the GPU drawing and H.264/AAC encoding of the highlight video
  can't run in the build environment (no phone or emulator); they reuse the app's existing video
  engine's way of decoding, drawing and encoding, and were reviewed line by line, but the first
  exports on real phones are the real test.

## What was new in 1.6.0: printing, without the printer's app

- **Print photos and PDFs** straight to a printer on your Wi-Fi — the Epson L3250 and other printers
  that support AirPrint, Mopria or IPP Everywhere (most current Wi-Fi printers). No printer app or
  driver: the app speaks the standard printing protocol (IPP) itself and draws the pages on the phone,
  in the formats these printers take (PWG raster, Apple raster, JPEG or PDF, whichever the printer
  prefers).
- **Finds your printer** on the Wi-Fi in a few seconds, or add it by IP address; it is remembered,
  and found again if the router gives it a new address. Works on a printer's own Wi-Fi Direct network.
- **See every sheet before printing**: the printer's own paper sizes (A4, Letter, 10 × 15, 13 × 18,
  9 × 13…) and paper types (plain, glossy, matte…), borderless where the printer allows it, 1–9 photos
  per sheet (whole photo or filling the space, turned to fit), PDF page ranges and actual size, colour
  or black & white, draft / normal / best (photo paper picks best), copies.
- **Plain-word status**: ready, printing, "Add paper", "Paper jam", cover open, ink as the printer
  estimates it; printing runs in the background with progress in the notification and Activity, and
  can be cancelled.
- **Print from anywhere**: the photo viewer and gallery selection, the results of any tool, and
  "Print (Local Media Tools)" in other apps' share menus (a PDF from e-mail or WhatsApp, say).
- **Privacy, enforced in code**: printing needs network access, so the app now has it — for the local
  network only. Every connection is checked to go to a local address (192.168.x, 10.x, 172.16–31.x,
  link-local, IPv6 local); printers are found with multicast DNS on the Wi-Fi and addressed by IP, so
  no name lookup can reach outside; a test fails the build if any other code touches the network.
  Encrypted printing (IPPS) is used when the printer offers it, trusting the printer's own
  certificate the first time and only that one afterwards.
- **Tested** with real photos against a simulated Epson L3250 (described the way Epson EcoTanks
  describe themselves over IPP) and against CUPS's reference IPP Everywhere printer, `ippeveprinter`,
  over plain and encrypted IPP; CUPS's own filters read the pages exactly as drawn
  (`buildtools/print/cups_check.sh`). Not yet on a real L3250.
- **Not possible this way**: printer maintenance (nozzle check, head cleaning) uses Epson's private
  protocol — use the printer's buttons or Epson's app for that.

## What was new in 1.5.0: one person, one group

People were too often split into several "people", or left out of their group, especially with a
cap, sunglasses, stage make-up, a big laugh or a face turned away. Measured on real photos: 802
faces of 78 people, checked by eye, in 1,233 Creative Commons photos taken by many photographers
over the years, with the 3,776 other faces in those photos as strangers (all faces at once, through
the app's own code):

| Share of a person's faces in their main group | 1.4.1 | 1.5.0 |
|---|---|---|
| All | 75 % | 87 % |
| With a cap, hat or visor | 54 % | 68 % |
| With glasses or sunglasses | 78 % | 86 % |
| With heavy make-up | 56 % | 75 % |
| With a strong expression | 77 % | 89 % |
| Turned | 75 % | 88 % |
| In profile | 43 % | 73 % |
| Small, far from the camera | 43 % | 68 % |
| **Faces put with the wrong person** | 0.2 % | 0.7 % |

The settings were tuned on half of the people and checked on the other half, which they had never
seen: there, 77 % → 87 % of each person's faces were in their main group, with no faces mixed up.

- **How** — a face is now compared with each group's *mean face* rather than with the average of all
  its faces, so someone photographed in very different conditions (whose photos agree less with each
  other) still forms one group; faces the detector is unsure about (blurry, half hidden) stay on
  their own instead of joining the wrong person; the "is this…?" and look-alike suggestions were
  recalibrated on the same photos.
- **Your library is regrouped once** after updating (photos aren't scanned again; names and
  corrections are kept).
- **Still hard** — dark sunglasses with a hat can make two people look alike (most of the wrong faces
  above), and in a small library someone whose looks vary a lot can still appear twice until more
  photos connect the two; a person's page suggests look-alike groups to merge.
- **Tests use real people** — the app's tests now run on 63 Creative Commons photos of nine public
  figures (credits in [`app/src/test/resources/people`](app/src/test/resources/people)): the real face
  models and grouping on the JVM, and the gallery screens (and screenshots) on real photos and faces.

## What was new in 1.4.1: Gallery hardening

A review of the Gallery aimed at what only shows up on real phones, and the fixes:

- **Recognition exactly as calibrated** — photos are now shrunk for the recognisers exactly the way
  they were during calibration (Pillow's resampling, reproduced bit for bit); all 2,000 test photos
  now get exactly the tags measured in calibration (1.4.0: 93 % did).
- **People and names are never lost by accident** — the library is never read without permission,
  a failed or empty read removes nothing, and a sudden loss of more than half of a big library only
  counts when a read half an hour later confirms it.
- **Background indexing ends cleanly** — errors, pausing and Android's time limits no longer leave
  the notification stuck "working"; a photo that keeps failing (or crashing the app) is skipped after
  two tries; results from a temporary failure are redone instead of being kept as "nothing found";
  while charging, indexing continues with the screen off.
- **Lighter on big libraries** — people are regrouped after a growing number of new faces (not every
  300), deleting photos no longer triggers a full regroup, open screens refresh only while visible,
  and only changed library entries are rewritten; naming someone during indexing takes effect at once.
- **Fixes** — faces from videos show the right frame; a person's page no longer closes another
  screen; with Android 14's "selected photos" access the Gallery offers to allow more; Android 10 can
  delete (one at a time: it has no trash); sharing and deleting take up to 500 items at a time, with a
  clear message beyond.

## What was new in 1.4: Gallery

- **Your photos and videos, organised on the phone** — a new Gallery tab shows everything by day
  (**Photos**), by folder (**Albums**), by person (**People**) and by what is in them (**Things**).
  The viewer shares, opens the photo editor, runs any tool on the photo, and deletes through
  Android's own confirmation. Works with "allow selected photos only", too.
- **Search in plain words** — names (*Sophie*; *John and Sophie* finds photos with both), things
  and places (*dog*, *beach*, *pizza*, *sunset*, *documents*), albums (*WhatsApp Images*), dates
  (*June 2023*, *last month*, *2024*) and kinds (*videos*, *screenshots*), freely combined: *Sophie
  at the beach 2024*. Small typos are forgiven and shown (*Jonh* → John).
- **People** — [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)
  finds faces, [SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface)
  recognises them, and faces of the same person are grouped automatically. The grouping is
  deliberately strict, so different people don't end up together: tuned on several hundred real
  faces of 16 people, it made no wrong merges in two of three trials (0.4 % in the third) while
  keeping most of each person's faces together (87 % by the usual B-cubed measure). Name a group once and new photos of that
  person join it. In the viewer, faces are outlined with names; tap one to name it, pick someone
  else, or say *this isn't Sophie* (remembered for good). Merge two groups, hide someone, review all
  of a person's faces, accept or reject *Also Sophie?* suggestions, and see **all faces** found,
  including small or blurry ones that are never grouped on their own.
- **Things** — 86 categories (people, animals, vehicles, food and drink, nature and places,
  rooms, sports, documents and more), recognised by two on-device models:
  [EfficientDet-Lite2](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector) finds objects,
  and [EfficientNetV2-B3](https://github.com/google/automl/tree/master/efficientnetv2) pre-trained
  on ImageNet-21k recognises 21,843 kinds of things and scenes, grouped into categories with
  WordNet, plus small recognisers trained for scenes it has no class for (sunsets, night,
  waterfalls, pools, weddings…). Videos are looked at in several frames.
- **Calibrated, not guessed** — every category's thresholds were set on 13,455 Open Images photos
  with human-verified labels, aiming at 90 % precision (a photo tagged *dog* shows a dog at least
  9 times in 10), with cross-validated results; 71 of the 86 categories reach at least
  85 % this way (median 90 %), the others 78–84 %. Categories that couldn't be recognised reliably were left out (shoes, hats,
  computers, churches, Christmas…). Per-category numbers: [docs/gallery-calibration.md](docs/gallery-calibration.md).
- **Runs quietly in the background** — at low priority with a quiet notification, pausing when the
  battery is below 15 % (unless charging) or the phone is hot; can be paused in Settings. New photos
  are picked up while the app is open (and each time it starts); deleted ones leave the index. The
  index stays on this phone.
- **Fallback** — on a phone where the AI models can't run, the gallery still works: Android's
  built-in face finder is used and faces are only grouped when near-identical, so people are named
  by hand. Auto enhance now uses the gallery's scene recogniser too.

## What was new in 1.3: tool stacks

- **Tool stack** (home screen, or **Then run another tool** at the bottom of any tool) — pick files
  once, add tools in order and run them with one tap. Each step works on the results of the step
  before, e.g. *Blur faces → Video compressor → Video → GIF → GIF optimizer*, or *Convert images →
  Background remover → Bulk watermark*.
- **Every option stays available**: adding or editing a step opens that tool's own screen (with
  previews of your files) and an **Add to stack** button; the stack lists each step with a short
  summary of its settings. Steps can be reordered, edited and removed.
- **Easy to follow**: between the steps the stack shows what flows on (photos, videos, GIFs,
  PDFs); the tool picker greys out tools that can't use what arrives at that point and says why.
- **Nothing gets lost**: in-between results are private temporary files (or saved too, if you
  switch that on); only the final files are saved, in the folders of the tools that made them. A
  file a step can't work on, or has nothing to do for (say it's already small), goes on unchanged.
  If a step fails for one file, the others continue.
- **Blur faces in a stack**: people you choose are recognised again by their faces at that step,
  so it also works after other steps; faces that look like no one you kept visible are hidden.
  **Trim** in a stack keeps the same part (as a share of the length) of every video.

## What was new in 1.2

- **Blur faces (photos and videos)** — [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)
  finds every face; [SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface)
  recognises the same person across frames and across all the selected files, so each person is
  listed once with a face picture. Tap who to hide (everyone is pre-selected), choose blur or
  pixelate and a strength. In videos, faces are tracked frame to frame (sampled several times a
  second, interpolated in between, never merging two faces seen at the same moment) and hidden on
  the GPU while re-encoding; the sound is kept as is. In photos, the hiding scales with the face so
  close-ups are anonymised too. The photo editor's Blur tab also has **Hide all faces**.
- **Background remover** — [U²-Net-p](https://github.com/xuebinqin/U-2-Net) (as packaged by
  [rembg](https://github.com/danielgatis/rembg)) finds the subject; a guided filter snaps the mask
  to real edges. Transparent PNG or white/black/grey background, optional crop to the subject,
  live preview, batch.
- **Auto enhance** — one tap in the editor's Adjust tab, or a batch tool. Faces (YuNet) and the
  kind of scene (EfficientNet-Lite0: food, landscape, plants, city, documents, animals, night…)
  steer exposure, contrast, highlights/shadows, white balance, vibrance and sharpening. The result
  is ordinary editor settings, so you can still fine-tune them.
- **Duplicate finder** — scans the photo library (asks for photo access only when you start it):
  identical files (SHA-256), re-saved/resized copies (difference hash) and near-identical shots
  (MobileNet-V3 image embeddings, with a looser threshold for bursts taken seconds apart). The
  sharpest, largest photo is marked as the best; copies are pre-selected; moving to the trash goes
  through Android's own confirmation and can be undone from the gallery for 30 days. Results are
  cached, so later scans only look at new photos.
- **Merge videos** — clips recorded with the same settings are joined without re-encoding;
  otherwise each is converted to a common size (letterboxed), frame rate and AAC sound (silence
  for clips without sound) and then joined.
- **Speed & timelapse** — 0.25× to 60×. Sound keeps its natural pitch (WSOLA time-stretching) up
  to 4×; muted copies up to 120 fps are only re-timed (no re-encoding); timelapses are smooth 30 fps.

## What was new in 1.1

- **Magic eraser** — brush over people or objects; [MI-GAN](https://github.com/Picsart-AI-Research/MI-GAN)
  (MIT) converted to TensorFlow Lite fills the gap on the phone. It works on a window around the
  stroke, so full-resolution photos keep their detail; edges are feathered and grain is matched.
  Falls back to a classic fill (OpenCV Telea) where the model can't run.
- **Photo editor** — crop with aspect presets, rotate, flip, straighten (auto-zoomed so no empty
  corners), 11 adjustments (exposure, brightness, contrast, highlights, shadows, saturation,
  vibrance, warmth, tint, sharpness, vignette), 10 looks with strength, undo/redo, hold-to-compare.
  Saving runs in the background at full resolution in memory-bounded strips.
- **Blur & pixelate** brushes to hide faces, number plates or addresses.
- **Trim & rotate video** (lossless), **Extract PDF pages**, **Remove metadata** (GPS, camera,
  dates, XMP, hidden thumbnails and extra images; pixels copied byte for byte, orientation and
  colour profile kept).
- **New design** — Tools / Activity / Settings navigation, tool search with synonyms, categories,
  Inter typeface, gradient tool icons, a new adaptive app icon with a themed (monochrome) layer.

## Tools

| Section | Tool | What it does | Saved to |
|---|---|---|---|
| Stacks | Tool stack | Runs any of the tools below in a row on the same files | Where the last tool saves |
| Edit & AI | Photo editor | Crop, rotate, straighten, adjustments, looks, auto enhance | `Pictures/LocalMediaTools/Edited` |
| | Magic eraser | On-device AI object removal | `Pictures/LocalMediaTools/Edited` |
| | Blur & pixelate | Privacy brushes, hide all faces | `Pictures/LocalMediaTools/Edited` |
| | Background remover | AI cut-out, transparent PNG or plain colour | `Pictures/LocalMediaTools/Cutouts` |
| | Auto enhance | AI-assisted one-tap fixes, batch | `Pictures/LocalMediaTools/Enhanced` |
| | Denoiser | Grain removed from photos, RAW files (CR2, DNG, NEF, ARW written back as themselves) and videos, as much as each file has | `Pictures/LocalMediaTools/Denoised`, `Movies/…/Denoised` |
| | Blur faces | Find faces in photos and videos, choose who to hide | `Pictures/…/Blurred faces`, `Movies/…/Blurred faces` |
| Images | Image compressor | JPEG/WebP, quality, max width (0 = original), never saves a larger file | `Pictures/LocalMediaTools/Compressed` |
| | Lossless image optimizer | Pixel-identical PNG or lossless WebP | `Pictures/LocalMediaTools/Compressed` |
| | Convert images | PNG/JPEG/WebP from HEIC, AVIF, TIFF, BMP, PSD, QOI, PNM, TGA, … | `Pictures/LocalMediaTools/Converted` |
| | Merge images | Vertical / horizontal / smart pack; no scaling; streamed in strips | `Pictures/LocalMediaTools/Merged` |
| | Multi-shot stitcher | Panoramas and flat scans in any arrangement, optional AI alignment assist | `Pictures/LocalMediaTools/Stitched` |
| | Bulk watermark | Text and/or logo, per-aspect-ratio placement | `Pictures/LocalMediaTools/Watermarked` |
| | Duplicate finder | Copies and similar shots in the photo library, best one kept | — (moves to the system trash) |
| Video | Split videos | Equal-length parts, stream copy | `Movies/LocalMediaTools/Splits` |
| | Trim & rotate video | Keep a range (start snaps to a keyframe), 90° turns, no re-encode | `Movies/LocalMediaTools/Trimmed` |
| | Lossless video optimizer | Remux to a fast-start MP4 | `Movies/LocalMediaTools/Optimized` |
| | Video compressor | H.264 re-encode, quality 20–100 | `Movies/LocalMediaTools/Compressed` |
| | Merge videos | Join clips in your order; lossless when possible | `Movies/LocalMediaTools/Merged` |
| | Speed & timelapse | 0.25×–60×, natural-pitch sound | `Movies/LocalMediaTools/Speed` |
| | Highlight video | Photos and videos of an event edited into a short film on the beat of on-device instrumental music or your own song; moments, best shots, captions | `Movies/LocalMediaTools/Highlights` |
| | Remove video audio | Video stream copied, audio dropped | `Movies/LocalMediaTools/NoAudio` |
| GIF | Video → GIF | FPS, width, source mode, adaptive colours | `Pictures/LocalMediaTools/Converted/GIF` |
| | GIF compressor | Width, FPS, 32/64/128/256 colours | `Pictures/LocalMediaTools/GIFs` |
| | GIF optimizer | Pixel-preserving re-pack | `Pictures/LocalMediaTools/GIFs` |
| Documents & PDF | File reader | Reads PDF, Word, Excel, PowerPoint, OpenDocument, RTF, EPUB, CSV and text files: zoom, search, contents, formulas, speaker notes; also from other apps' "Open with" | — (only shows files) |
| | PDF → images | PNG/JPEG, scale 50–400 % | `Pictures/LocalMediaTools/PDF Images` |
| | Images → PDF | A4 pages in your order | `Documents/LocalMediaTools/PDF` |
| | Merge PDFs | Structural merge: text and links kept | `Documents/LocalMediaTools/PDF` |
| | Extract PDF pages | `1-3, 5, 8-` into one PDF or one per page | `Documents/LocalMediaTools/PDF` |
| | PDF scanner | Camera and gallery pages, A4 | `Documents/LocalMediaTools/PDF` |
| Print | Print | Photos and PDFs on AirPrint / Mopria / IPP Everywhere printers on the Wi-Fi: paper size and type, borderless, 1–9 per sheet, colour, quality, copies, preview | — (prints) |
| Privacy & audio | Remove metadata | Lossless clean copies of JPEG/PNG/WebP/GIF and videos; RAW photos as clean full-quality copies like the other files | `Pictures/…/Clean`, `Movies/…/Clean` |
| | Extract audio | AAC track to M4A without re-encoding | `Music/LocalMediaTools/Extracted Audio` |

Exports run in a foreground service with progress notifications and one queue for the whole app.
The **Export workload** setting (10–100 %, Efficient / Balanced / Fast) controls parallelism,
thread priority, pauses and the memory budget, live, even mid-export.

## Building

No Android SDK or Gradle is needed; the build uses a pinned, self-downloaded toolchain
(aapt2, android.jar 35, Kotlin compiler, D8, apksigner).

```bash
python3 buildtools/fetch_toolchain.py                # once: downloads + verifies the toolchain into .toolchain/
python3 buildtools/build_apk.py                      # -> build/LocalMediaTools.apk (signed)
python3 buildtools/build_apk.py --test               # + JVM unit tests
python3 buildtools/fetch_toolchain.py --robolectric  # once, optional (needs mvn)
python3 buildtools/build_apk.py --robo-test          # + Android-framework tests in Robolectric
```

The on-device models are committed in `app/src/main/assets/models/` with their licences in
`app/src/main/assets/licenses/` (also shown in Settings → Open-source licences): YuNet (MIT) from the
OpenCV Zoo, MobileFaceNet "w600k_mbf" from InsightFace (weights for non-commercial research use only;
`buildtools/gallery/face_model.py` makes the app's 16-bit copy from InsightFace's release), U²-Net-p (Apache-2.0, via rembg, MIT), EfficientDet-Lite2
(Apache-2.0, MediaPipe), EfficientNetV2-B3 ImageNet-21k (Apache-2.0, Google AutoML) and the decoder
of Magenta's MusicVAE trio model (Apache-2.0, Google; run by the app's own Kotlin code, checked
against magenta.js, see [`buildtools/music/`](buildtools/music/README.md)). The ONNX
models run through OpenCV's DNN module, the TFLite ones through TensorFlow Lite. How the gallery's
recognition files were made and calibrated (EfficientNetV2 with int8 weights, its 4-bit output
layer, the probes and the category table) is in [`buildtools/gallery/`](buildtools/gallery/README.md). The eraser model (`migan_512_fp16.tflite`, 14 MB) is committed too;
`buildtools/models/convert_migan.py` reproduces it byte for byte from the published MI-GAN weights
(TorchScript → ONNX → TFLite float16 with TensorFlow 2.16.1, matching the app's TFLite runtime) and
checks it against PyTorch. Google's Maven repository wasn't reachable from the build environment,
so the app uses only the Android framework (no AndroidX) plus kotlinx-coroutines, OpenCV,
pdfbox-android and TensorFlow Lite from Maven Central.

The release APK is signed with the sideload key in `buildtools/signing/` (password `localmediatools`)
so updates install over each other; use your own key for a store release.

## Tests

* `app/src/test` — 161 JVM tests: codecs, orientation, layouts, MP4 fast start, stitching, editor
  geometry and colour pipeline, masks and mosaics, the metadata stripper (JPEG/PNG/WebP/GIF), the
  real vision models (face detection and recognition, tracking and grouping people across photos
  and videos, cut-out masks, auto enhance, duplicate grouping), the sound pipeline (resampling,
  channel mixing, pitch-preserving speed changes) and the gallery core: detector decoding against
  the model's anchors, people grouping (no mixed groups, names and "not this person" respected,
  blurry faces only joining clear matches; on 63 real photos with the real face models: everyone
  found again, nobody mixed up, and most of each person's faces grouped together with caps, glasses,
  make-up, expressions, turned and small faces), search parsing (names, typos, dates, albums, kinds),
  score fusion, resizing bit-identical to Pillow, and the shipped 4-bit scene layer and category table
  reproducing the calibration's scores on real photos' features; and printing: IPP messages, the
  local-network rule (and a scan of the source that nothing else uses the network), finding printers
  from their announcements, PWG and Apple raster decoding to the exact pixels, sheet layout, and whole
  jobs of real photos to a simulated Epson L3250 (paper, borderless, grayscale, quality, copies, a
  printer that refuses streamed jobs, AirPrint-only and JPEG-only printers, no paper, cancelling).
  `buildtools/print/cups_check.sh` also prints to CUPS's reference IPP Everywhere printer
  (`ippeveprinter`, plain and encrypted) and has CUPS's own filters read the pages back. New in 1.7:
  the duplicate finder on real photos (same moments and re-saved copies grouped; other angles and the
  hardest look-alikes — clouds, night skies, printed pages, screenshots taken seconds apart — never;
  the thumbnail layout matching the evaluation's; keep rules; 20,000 photos compared in seconds), name
  suggestions on the 802 labelled faces (none wrong), the music model decoding exactly like
  magenta.js, composing (repeatable, in key, long enough) and synthesis (no clipping, steady level,
  fades), capture times from photos and videos, moments and their names, the edit (chronological, on
  the beat, bursts once, choices kept, the liveliest part of videos), each frame's layers and fades,
  texture coordinates for every video rotation, the sound mix with the music stepped back, and real
  people's faces kept in frame on every frame in all three shapes. New in 1.8: the new face model on
  the real photos (the app's Kotlin pipeline giving the evaluation's embeddings, its alignment giving
  OpenCV's crops, its self-check, and grouping at least as well as SFace on the same photos). New in
  1.9: every language's catalog against the code (every text translated, placeholders and plural forms
  intact, date patterns valid and complete, search words that don't clash), each language's plural
  rules and number and date formats, search in Portuguese, Chinese, Japanese and Korean, shortening
  names by whole characters (emoji included), and auto enhance's model on real portraits. New in
  1.10: the RAW reader and writer on pieces of real camera files (a Pixel 4a DNG with the phone's own
  compressed tiles, a Samsung DNG): sensor data identical to LibRaw's, unchanged data giving the
  same file, changed data coming back exactly, lossless JPEG on the camera's own data and on extreme
  values, and 480 damaged copies failing cleanly; the denoisers: known noise found, fine detail not
  taken for noise, strips giving the whole picture's result, a real RAW file's noise removed without
  moving its levels or its clipped highlights, video averaging still parts and leaving no trail
  behind moving ones; the song code on music of known tempo (beat, start on a beat, silence skipped,
  repeating on whole bars, level, no beat in a held chord); the Gallery's choice of video frames;
  the duplicate finder's progress (the grouping's share done only moving forward, the time left);
  and how close a full-quality JPEG stays to the picture on 87 real photos. New in 1.11: the
  document readers on real documents (a NASA report, a USDA spreadsheet, an EPA PDF, Apache POI's
  samples) in every format they read — text, tables, pictures, notes, formulas, merged cells, slides,
  locked files saying so — and Excel's number formats as Excel shows them (rounding, General,
  sections and colours, dates and times, fractions, each language's separators and month names),
  each case standing for a difference found against LibreOffice on 2.9 million real cells.
* `app/src/roboTest` — 78 Robolectric tests (Android 15 runtime, native graphics): EXIF orientation
  through decoding and export; every image, GIF and PDF tool end to end; the editor at full
  resolution (rotation, flip, crop, straighten, colours equal to the preview pipeline, eraser and
  privacy brushes); the editor UI (brush stroke → erase → undo/redo → rotate → save); metadata
  removal keeping photos upright; page extraction; background removal, auto enhance and photo face
  blurring end to end (with stand-ins for the native models); tool stacks (real tools chained end to
  end, temporary in-between files, files passing steps that can't use them, merging, failures,
  and building and running a stack in the UI); the gallery on a stand-in photo library of real photos and faces (indexing,
  grouping people, naming, search with typos, face names in the viewer, "not this person"
  surviving regrouping, removed photos leaving the index, empty or failing library reads and a
  sudden big drop not wiping the index, failing photos retried once, pausing, every gallery screen,
  and updating a library from 1.7.0 to the new face model with every name and correction kept);
  printing from the Print screen to the simulated L3250 (finding it, its state and ink, its paper
  sizes and types, borderless photos two per sheet, the job it receives; a printer added by IP
  address falling back from encrypted to plain IPP; files shared from other apps); the duplicate
  finder's screens on real photos (suggestions, a tap changing one photo, keep rules, RAW files and
  their best copy, never suggesting photos of a person you keep, the one-by-one review); naming
  people one after another; the highlight video's timeline on real photos of people (moments, the
  draft edit, always in / left out, shape, length, renaming, music, and listening to the music the
  model writes); a pass that opens every tool from the home screen; and, new in 1.9, the home screen,
  every tool's screen and every tab opened, laid out and drawn in each of the nine other languages,
  with no English titles and no unfilled placeholders; new in 1.9.1, the duplicate finder's real scan
  end to end on real photos (see above), and a scan that can read no photo saying so; new in 1.10,
  RAW files recognised by their contents, taken by every tool with a warning before the ones that
  would save them as another type (in tool screens and stacks; Cancel starts nothing, Continue the
  export), RAW files the Denoiser can't write back or that are
  damaged reported with the reason, picking your own song for the highlight video (its beat shown
  and used, kept while trying the app's tunes, a cancelled pick changing nothing), and the Gallery
  looking at only the videos again after updating, with what was said about their faces kept; new
  in 1.10.1, the duplicate finder's progress (one bar that moves on instead of being rebuilt, the
  photo count, share done and time left, filled in the accent colour) and the real scan's progress
  only ever moving forward; new in 1.10.3, RAW photos following your format choice (converter,
  compressor, optimizer), and a real phone DNG through Auto enhance, Bulk watermark and Remove
  metadata like the files around it (a pixel-identical PNG among PNGs, otherwise a full-quality JPEG:
  every quantisation step 1, full colour resolution, no metadata); new in 1.11, the file reader end
  to end on real documents: opened from the tool, the system's file picker and another app's "Open
  with", Word text searched and zoomed, a spreadsheet's formula bar and cell search (.xlsx and .xls),
  slides from .pptx, .ppt and .odp drawn with their notes, PDF pages drawn and searched, the recent
  list keeping a copy of a lent file, and locked and unknown files saying so.
  `LMT_SHOTS=<dir>` also renders the screenshots above.

Not covered by automated tests (no emulator here): a real printer (see above for what printing is
tested against), MediaCodec/MediaExtractor video paths (including the GPU face blur, the GPU video
denoiser, merging and speed changes, the highlight video's analysis of videos, decoding your song, GPU
drawing, H.264 and AAC encoding, and the Gallery's video frames), PdfRenderer (also used to print PDF
pages and to show them in the file reader; the tests draw PDFs with the PDFBox fallback), BitmapRegionDecoder, the camera intent,
the photo-library scan and trash request, MediaStore and the background indexing service on a real
phone, and the native OpenCV/TFLite code on a device (the face and cut-out models are tested on the
JVM with OpenCV's desktop build, the eraser model against PyTorch, and the gallery's TFLite models
were measured with the same TensorFlow Lite version in Python, the app's Kotlin code then being
checked against those measurements).
