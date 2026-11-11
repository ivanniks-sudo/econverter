# eConverter

<a href="https://f-droid.org/repository/browse/?fdid=com.econverter.app" target="_blank">
<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="48" align="left" /></a><br /><br />

[![F-Droid](https://img.shields.io/f-droid/v/com.econverter.app.svg)](https://f-droid.org/en/packages/com.econverter.app/)
[![GitHub release](https://img.shields.io/github/release/bilec/econverter.svg)](https://github.com/bilec/econverter/releases)
[![Release](https://github.com/bilec/econverter/actions/workflows/release.yml/badge.svg)](https://github.com/bilec/econverter/actions/workflows/release.yml)

Android app for converting e-books individually or in batches with Calibre and boko.

## Usage

1. **Select eBooks**, then choose an **Output Format**. **Add eBooks** extends the queue.
   You can also select one or more books in a file manager (such as Solid Explorer),
   tap **Share**, and choose **EbookConverter**. Readable supported files are added
   to the queue; unsupported or inaccessible files are reported. If conversion or
   saving is in progress, incoming books wait until it finishes.
2. Optionally open **Conversion options** for Metadata, Typography, Margins, or
   Advanced settings (extra arguments and text removal). Closing a group keeps its values.
3. Tap **Convert**, then **Save** to a folder or **Share** the results.
   **Gmail** opens a new message directly in Gmail with the results attached;
   before conversion, it attaches the selected books instead. Address the message
   and send it yourself. Gmail must be installed and available.

Each EPUB, AZW3, or MOBI has **Edit metadata**, including in a multi-book queue.
Choose **Create edited copy**, then **Save** or **Share**; originals and the queue
stay unchanged. Cancelling an update returns to the editor with the draft retained;
failed updates also keep the draft for retry.
Metadata reads and updates exceeding 64 KiB of serialized data are rejected,
not truncated.

Shared output files are retained for one hour, including across app restarts,
then become eligible for cleanup.

**Cancel** discards all temporary outputs from the current batch, including completed
books; originals are unchanged.

Swipe a selected book left or right to remove it from the queue. **Clear all**
deselects all books. **History** restores a previous selection, output format, and
backend for the current app session.

### ZIP sources

Select or share `.zip`/`.fb2.zip` files just like ordinary books (including from
Solid Explorer). Each archive can contain several supported books, in nested
folders, with UTF-8 names including Cyrillic (legacy CP866 names are also supported). Multiple archives and ordinary
books can be imported together. The selected backend determines which books are
accepted. ZIP is a source container, not an output format. Nested ZIPs and other
unsupported entries are skipped and reported; embedded resources alongside HTML
books are not imported.

Each archive is validated before its books enter the queue. An invalid, damaged,
encrypted, unsafe, or oversized archive is rejected as a whole; other sources in
the selection can still be imported. Empty archives and archives containing no
supported books are reported. Original archives are never modified.

Safety limits (binary MiB):

- 128 MiB compressed per archive; 256 MiB compressed across one import request.
- 64 MiB per uncompressed entry; 256 MiB of extracted ZIP books across the queue.
  All declared entry sizes, including skipped files, must also total at most
  256 MiB per archive. Actual extracted byte counts are checked while streaming.
- 2,048 entries (including directories/skipped files), 256 books, and 64 ZIPs per
  import request. Previously selected ZIP books count toward the book/byte limits.
  Work already spent on rejected archives still counts toward that request budget.
- Compression ratio at most 200:1 for entries larger than 1 MiB; central directory
  at most 4 MiB. ZIP64, multi-disk, encrypted archives, and filename encodings
  other than UTF-8/CP866 are not supported. Archive files and entries are streamed
  with 32 KiB buffers.

Absolute paths, parent traversal, and Windows drive paths are rejected. Entries
are stored as regular files under app-private numbered directories, so matching
basenames do not overwrite each other. CRC and size checks detect damaged data.

Extracted inputs survive Activity recreation in the retained ViewModel and are
not added twice. They are deleted on removal, Clear all, ViewModel disposal, or
after conversion finishes, fails, or is cancelled. After conversion, ZIP-derived
books leave the selection; ordinary source books and output Save/Share actions
are preserved. History retains original archive URIs and imports them again when
selected. Temporary inputs already attached to Gmail are retained for one hour,
matching the existing output-sharing policy. Startup purges abandoned unshared
imports and expired shared imports. Import failures/cancellation remove partial
files. A fresh process can reimport the launch Intent if its URI grants remain
valid; History is still limited to the current app session.

## Formats and Backends

**Input (Auto):** epub, mobi, azw3, docx, odt, fb2, html, htmlz, lrf, pdb, rtf, txt, md, markdown, pdf, kfx

**Output (Auto):** epub, kfx, mobi, azw3, docx, fb2, htmlz, lrf, md, txt, txtz

> PDF input must contain text. Scanned PDFs require OCR, which is unavailable on Android.

Choose a backend in **Settings**; the choice is remembered.

| Backend | Formats and Options |
| --- | --- |
| Auto (default) | All formats above. Combines Calibre and boko through intermediate EPUBs when needed; Calibre options apply. |
| Calibre | Auto's formats except KFX input and KFX/Markdown output. |
| boko | Input: epub, kfx, azw3, mobi. Output: epub, kfx, azw3, md. Native conversion; Calibre options are hidden but retained. |

Switching backend keeps selected books. Incompatible books block conversion until
cleared or a compatible backend is chosen. Unsupported output choices reset to EPUB.

## Build

Requires JDK 17+, Android SDK platform 37, and Python 3.11 (`python3.11` on PATH,
or `CHAQUOPY_BUILD_PYTHON` set to its absolute path). Android Studio is optional.

Install Android NDK r30 with `sdkmanager "ndk;30.0.16248370"` to strip native
library symbols; without it, Gradle packages the libraries unchanged.

| Command | Purpose |
| --- | --- |
| `./gradlew assembleDebug` | Build debug APKs for every ABI flavor (`armeabi-v7a`, `x86_64`, `arm64-v8a`, `universal`). |
| `./gradlew installUniversalDebug` | Install on a connected device or emulator. |
| `./gradlew check` | Run lint, formatting checks, and unit tests. |
| `./gradlew :app:connectedUniversalDebugAndroidTest` | Run device/emulator tests. |

Engine versions are pinned in [gradle.properties](gradle.properties) by
`EBOOK_CONVERTER_LIB_VERSION`, `PYPDFIUM2_VERSION`, and `BOKO_ANDROID_VERSION`.
The PDFium version must match ebook-converter-lib's pin; its x86_64 wheel comes
from the lib's `pypdfium2-<version>-android` release rather than PyPI.

For F-Droid release work, initialize the pinned [CPython submodule](.gitmodules)
with `git submodule update --init --depth 1 cpython`. Normal builds use installed
Python. Build the host interpreter with
`./configure --with-ensurepip=no --disable-test-modules` to skip test-only
extensions, then verify `ssl`, `zlib`, `bz2`, `lzma`, and `ctypes` imports.
See [CODEBASE_MAP.md](CODEBASE_MAP.md) for the architecture.

### CI Debug APKs

Run **Debug APK** manually from **Actions**, selecting the branch to build
(including feature branches with open PRs). This workflow builds debug APKs
without running tests or requiring deployment approval.
Download `debug-apks-*` from the run's **Artifacts** (GitHub sign-in required).
Artifacts expire after 14 days; no release is created.

## Credits

- [ebook-converter](https://github.com/gryf/ebook-converter), packaged as
  [ebook-converter-lib](https://github.com/bilec/ebook-converter-lib).
- [boko](https://github.com/zacharydenton/boko) by Zachary Denton, via our Android bindings.
- [Python](https://www.python.org/) and [Chaquopy](https://chaquo.com/chaquopy/) for the embedded runtime.
- [PDFium](https://pdfium.googlesource.com/pdfium/) and
  [pypdfium2](https://github.com/pypdfium2-team/pypdfium2) for PDF input.

The ebook-converter-lib package's KF8/AZW3 writer (`ebook_converter.ebooks.mobi.writer8`)
and HTML export templates (`ebook_converter/data/html_export_default*`) are ported
from [calibre](https://github.com/kovidgoyal/calibre) (GPL v3, © Kovid Goyal).
The templates were adapted from Mustache to templite syntax.

## Translations

Conversion engine diagnostics may remain untranslated.

Community translations are welcome! To add a new language:

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-<lang>/strings.xml` (e.g. `values-de/strings.xml`).
2. Add your language tag to `app/src/main/res/xml/locales_config.xml`.
3. Submit a pull request with your changes.

To request a language or share translations without a pull request, open an issue
using the [translation template](.github/ISSUE_TEMPLATE/translation.md).

## Screenshots

| Conversion | Backend Settings |
| --- | --- |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" alt="Main conversion screen with a selected e-book" width="250" height="556" /> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" alt="Conversion backend settings" width="250" height="556" /> |
| **Options** | **Save and Share** |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" alt="Expanded conversion options" width="250" height="556" /> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" alt="Completed conversion with Save and Share actions" width="250" height="556" /> |
| **Metadata Editor** | **Dark Mode** |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" alt="Metadata editor showing book title, authors, publisher and comments" width="250" height="556" /> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" alt="Main conversion screen in dark mode" width="250" height="556" /> |
