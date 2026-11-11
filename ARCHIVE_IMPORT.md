# RAR and 7z source import

This work starts at `49a64060106bd5448409ffb05a7d66743eafec7e` on
`feat/rar-7z-import`. ZIP decoding (`ZipBookImporter.kt`) is unchanged.
Archives are sources of books, not conversion input/output formats. No TAR,
TAR.GZ, TGZ or TAR.XZ import is enabled, including renamed files and nested archives.

## Library decision

| Candidate | RAR4 / RAR5 / 7z | Android, licensing, memory and size assessment |
| --- | --- | --- |
| [Junrar](https://github.com/junrar/junrar) | Yes / No / No | Java, LGPL-2.1; cannot satisfy RAR5 or 7z. Not selected. |
| [SevenZipJBinding](https://sevenzipjbind.sourceforge.net/) / UnRAR-based integrations | Native, varies by version | Desktop native distributions require Android/ABI work. LGPL/UnRAR licensing needs additional scrutiny for a GPL application. No UnRAR-derived decoder is added. |
| [libarchive-android 1.1.7](https://github.com/zhanghai/libarchive-android/tree/v1.1.7) | Yes / Yes / Yes | Selected only for RAR. Apache-2.0 Java binding and BSD libarchive 3.8.8, plus permissively licensed bundled codecs. Android minSdk 21; AAR contains arm64-v8a, armeabi-v7a, x86 and x86_64. Existing application ABI filters retain only arm64-v8a, armeabi-v7a, x86_64. Streaming RAR entry reads; native decoder dictionaries have format-specific limits. Its 7z reader has no configurable LZMA memory ceiling, so it is deliberately not enabled. |
| [Commons Compress 1.28.0](https://commons.apache.org/proper/commons-compress/) + [XZ for Java 1.12](https://tukaani.org/xz/java.html) | No / No / Yes | Selected for 7z. Apache-2.0 and 0BSD, pure Java, architecture independent, configurable decoder memory limit. Commons Codec is excluded because the SevenZ reader does not use it. Commons IO/Lang remain required. Core-library desugaring supports dependency APIs on Android 24/25. |

GPLv3 distribution uses the permissive license options above (including Apache-2.0
for bundled Mbed TLS). Core-library desugaring uses OpenJDK code under GPLv2
with the Classpath Exception (per its published POM), which permits linking the
library into this GPLv3 app. Its license/exception notices are shipped too;
corresponding upstream sources are at https://github.com/google/desugar_jdk_libs. Notices for the binding, libarchive/RAR/PPMd, bzip2,
liblzma, LZ4, Zstandard, zlib, Mbed TLS and Java dependencies are shipped under
`app/src/main/assets/archive-licenses/`. JNA is test-only and is not in the APK.
The prebuilt libarchive AAR requires compile SDK 37.2; `compileSdkMinor` is raised
accordingly. minSdk 24 and targetSdk 37 are unchanged. No native compiler or new
runtime Python packages are required for these readers.

## Accepted formats and limits

- RAR4 stored/compressed and RAR5 stored/compressed, including solid streams within
  libarchive's supported methods/windows. Unicode paths and duplicate basenames
  are preserved as book names, with unique private numbered storage directories.
- 7z supports common Copy/LZMA/LZMA2 and Commons Compress-supported filter chains.
  Solid archives are read sequentially, draining unsupported entries with the
  same inflated-byte accounting. Nested ZIP/RAR/7z/TAR and other unsupported
  entries are skipped, not recursively opened.
- Passwords, encrypted headers/files, split/multi-volume archives, SFX prefixes,
  links/reparse points, anti-items and unsupported codecs are rejected. Unknown
  or complex encoded-header layouts are rejected rather than guessed.
- Shared limits: 128 MiB compressed per archive, 256 MiB compressed per request,
  64 MiB per expanded entry, 256 MiB expanded across the request/selected archive
  books, 2,048 entries, 256 books, 64 archives per request. Failed work still
  consumes that request's budget. Skipped RAR/7z files also consume expanded bytes
  because solid streams can require their decompression. Data ratios exceeding
  200:1 after 1 MiB of expansion are rejected. Declared sizes and actual streamed
  byte counts are checked, with rollback of the entire failing archive.
- 7z: 64 MiB limit per decoder; initial and decoded headers at most 4 MiB.
  Encoded headers are limited to one packed stream and one folder, up to 16 simple
  one-input/one-output coders. This preflight prevents allocation of a huge
  metadata buffer before Commons Compress can enforce its dictionary limit.
- RAR: framed headers and CRCs are checked before native decoding, with headers
  at most 2 MiB for RAR5 and 65,535 bytes for RAR4. End markers are required.
  The pinned RAR5 reader rejects dictionaries above 64 MiB. RAR4 LZSS uses up to
  4 MiB, but legacy PPMd can allocate up to 256 MiB. RAR admission rejects devices
  reported as low-RAM/low-memory, or with less than 512 MiB available memory.
  **There is no uniform 64 MiB process-RSS ceiling for RAR4/PPMd.** For low-memory
  devices, use ZIP or a 7z archive with a smaller dictionary instead.

Sources are copied to bounded private disk files and decoded with 32 KiB buffers;
archives are not loaded wholesale into RAM. Absolute paths, parent traversal,
Windows drive paths, special files and links are rejected. No archive entry can
choose its parent directory. Decoder CRC checks and exact expanded lengths reject
corrupt payloads where the format supplies checksums.

`SelectionImporter` feeds the existing queue for picker, SEND and SEND_MULTIPLE.
The existing source map/storage names (`zipSources`, `zip-imports`) are retained
for compatibility. Deduplication, busy-app deferral, retained ViewModel handling,
History reimport and one-hour Gmail attachment retention cover all three formats.
Unshared inputs are removed on deselection, disposal and conversion completion,
error or cancellation. Outputs remain in the separate `conversion-runs` tree.

## ZIP result report and verification

A reported issue was: Auto -> EPUB shows "Done - choose where to save", the queue
is empty, and Gmail/Share appears to have no attachment. A confirmed UI defect
was that ZIP inputs leave the queue after conversion but output names had no
separate display. Pending results now appear under "Converted files". Receiving
worker results are checked for nonempty readable files; Share/Gmail also check
result URI readability and report an error instead of launching an empty draft.
Missing outputs never fall back to mailing the original input.

This fixes the confirmed display defect and adds guards against false success.
It does **not** establish the cause of the reported empty attachments on the
physical device. A new Android smoke test performs real shared ZIP -> Auto -> EPUB
conversion, checks an XHTML marker, visible output names, and readable Share/Gmail
URIs after archive input cleanup. It must be run on Android before claiming that
end-to-end device symptom is resolved.

## Verification

Unit tests cover real RAR4/RAR5 compressed and stored fixtures, solid RAR5,
Cyrillic/multiple/nested books, 7z compressed metadata, corrupt/encrypted/split
archives, paths/links, resource budgets, dictionary/header bombs, cancellation,
mixed picker/Share batches, deduplication, History and cleanup. Existing ZIP,
Share and Gmail tests are retained. New result tests cover false success,
disappearing files and refusal to launch empty drafts.

Host RAR tests use Linux `libarchive.so.13` via test-only JNA. They exercise real
RAR payloads, but not the Android JNI binary. `Rar7zReaderDeviceTest` exercises
that shipped binary for all three target ABIs when run on the appropriate device.
Both that test and the real ZIP conversion test are compiled as part of the
verification below; device execution remains required.

Verified in this environment: **75 app unit tests and 5 buildSrc tests passed**,
`spotlessCheck`, `assembleUniversalDebug`, and Android-test Kotlin compilation
passed. Device smoke tests were compiled, not executed. The runtime has no
configured Android emulator/KVM; the adb client also cannot create its default
settings directory in the read-only home. Therefore physical ZIP -> EPUB and
external Gmail/Solid Explorer behavior are explicitly not claimed as verified.

```sh
./gradlew :app:testUniversalDebugUnitTest :buildSrc:test :app:spotlessCheck \
  :app:assembleUniversalDebug :app:compileUniversalDebugAndroidTestKotlin
# On a configured device/emulator:
./gradlew :app:connectedUniversalDebugAndroidTest
```

Cloud checks use a writable Gradle/test cache, the configured network proxy,
JDK 21 and Python 3.11 outside the repository. The test JVM's user directory is
redirected via a temporary init script because the default home is read-only.

## APK size comparison

| Build | APK bytes | MiB | Change from baseline |
| --- | ---: | ---: | ---: |
| ZIP baseline `49a6406` | 72,161,924 | 68.82 | +0 bytes (+0.00%) |
| Baseline + dependencies/build support only | 77,773,749 | 74.17 | +5,611,825 bytes (+7.78%) |
| Final RAR/7z + ZIP-result fixes | 77,846,708 | 74.24 | +5,684,784 bytes (+7.88%) |

Dependency-only increase: **5,611,825 bytes / 5.35 MiB**.
Total increase: **5,684,784 bytes / 5.42 MiB**.
Remaining code/messages/license-asset overhead: **72,959 bytes**.

Compressed `libarchive-jni.so` payload by ABI:

| ABI | Bytes in APK |
| --- | ---: |
| arm64-v8a | 1,175,236 |
| armeabi-v7a | 1,109,554 |
| x86_64 | 1,309,190 |

SHA-256 of measured artifacts:

- baseline: `1a2a3875077e2c880ffff8683a9266e7264d6689da66ff64a4b3bb6a110920ca`
- dependencies: `509dcb6202422cb1dcbc290a8f4b1ce83988e9ee7099b7aa3ed3d003fe93e06b`
- final: `cde3e77f814eda1c4bb3e8d3c8f340b4d00bc9cbbf6d89090445a0aa4b68fb15`


The baseline is a detached checkout of the exact checked commit. All builds use
Gradle 9.8.2, JDK 21, the same debug signing key, Python 3.11, version 1.5.0,
universal debug flavor and the same three ABIs. Dependency-only control uses the
baseline sources plus the selected dependencies (without Commons Codec), required
desugaring and compile SDK 37.2. It contains no new importer/UI code or license
assets. Thus its delta isolates the new dependency/build-support cost; the final
remaining delta includes importer/result fixes, messages and attribution assets.

APK output files are removed before the final package task, avoiding retained
free blocks from incremental packaging; the source/build caches are retained.
These are debug APKs, not R8-minified release or ABI-specific APKs. Existing ABI
filters avoid the extra x86 native library, and JNA/test fixtures are absent from
production APKs. No TAR reader is enabled by the application despite additional
format code being present internally in the libraries.

## Changed files

- `.gitignore`
- `AGENTS.md`
- `ARCHIVE_IMPORT.md`
- `CODEBASE_MAP.md`
- `README.md`
- `app/build.gradle.kts`
- `app/src/androidTest/java/com/econverter/app/ConversionWorkerTest.kt`
- `app/src/androidTest/java/com/econverter/app/Rar7zReaderDeviceTest.kt`
- `app/src/main/assets/archive-licenses/bzip2.txt`
- `app/src/main/assets/archive-licenses/commons-compress-LICENSE.txt`
- `app/src/main/assets/archive-licenses/commons-compress-NOTICE.txt`
- `app/src/main/assets/archive-licenses/commons-io-LICENSE.txt`
- `app/src/main/assets/archive-licenses/commons-io-NOTICE.txt`
- `app/src/main/assets/archive-licenses/commons-lang3-LICENSE.txt`
- `app/src/main/assets/archive-licenses/commons-lang3-NOTICE.txt`
- `app/src/main/assets/archive-licenses/desugar-additional.txt`
- `app/src/main/assets/archive-licenses/desugar-exceptions.txt`
- `app/src/main/assets/archive-licenses/desugar-jdk.txt`
- `app/src/main/assets/archive-licenses/libarchive-android-apache2.txt`
- `app/src/main/assets/archive-licenses/libarchive.txt`
- `app/src/main/assets/archive-licenses/lz4.txt`
- `app/src/main/assets/archive-licenses/mbedtls.txt`
- `app/src/main/assets/archive-licenses/rar-decoders.txt`
- `app/src/main/assets/archive-licenses/xz-java.txt`
- `app/src/main/assets/archive-licenses/xz-native-0BSD.txt`
- `app/src/main/assets/archive-licenses/xz-native.txt`
- `app/src/main/assets/archive-licenses/zlib.txt`
- `app/src/main/assets/archive-licenses/zstd.txt`
- `app/src/main/java/com/econverter/app/BookArchiveReader.kt`
- `app/src/main/java/com/econverter/app/ConversionService.kt`
- `app/src/main/java/com/econverter/app/ConverterViewModel.kt`
- `app/src/main/java/com/econverter/app/MainActivity.kt`
- `app/src/main/java/com/econverter/app/OtherBookArchiveImporter.kt`
- `app/src/main/java/com/econverter/app/RarHeaderGuard.kt`
- `app/src/main/java/com/econverter/app/SelectionImporter.kt`
- `app/src/main/java/com/econverter/app/SevenZHeaderGuard.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/econverter/app/ConversionResultTest.kt`
- `app/src/test/java/com/econverter/app/ConverterViewModelTest.kt`
- `app/src/test/java/com/econverter/app/GmailSharingTest.kt`
- `app/src/test/java/com/econverter/app/HostRarReader.kt`
- `app/src/test/java/com/econverter/app/OtherBookArchiveImporterTest.kt`
- `app/src/test/java/com/econverter/app/Rar7zSelectionTest.kt`
- `app/src/test/java/com/econverter/app/SharedFilesTest.kt`
- `app/src/test/java/com/econverter/app/ZipSelectionTest.kt`
- `app/src/test/resources/archives/README.md`
- `app/src/test/resources/archives/books.7z`
- `app/src/test/resources/archives/encrypted.7z`
- `app/src/test/resources/archives/generate_stored_rar.py`
- `app/src/test/resources/archives/rar4-books.rar`
- `app/src/test/resources/archives/rar4-compressed.rar`
- `app/src/test/resources/archives/rar4-volume.rar`
- `app/src/test/resources/archives/rar4.rar`
- `app/src/test/resources/archives/rar5-books.rar`
- `app/src/test/resources/archives/rar5-encrypted.rar`
- `app/src/test/resources/archives/rar5-solid.rar`
- `app/src/test/resources/archives/rar5-volume.rar`
- `app/src/test/resources/archives/rar5.rar`
