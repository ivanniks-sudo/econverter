# Archive fixtures

These files are test data, not production assets. `generate_stored_rar.py` creates
`rar4-books.rar` and `rar5-books.rar` with stored FictionBook entries, including
nested Cyrillic names and duplicate basenames; it contains no RAR compressor.
`books.7z` and `encrypted.7z` were generated with py7zr (two stored test strings
as FictionBook content; the encrypted fixture password is `fixture-password`).

Other fixtures come from libarchive commit
`7219b0134d771dc4b51bf86b4d01761b87398b1b`, under its BSD license:
https://github.com/libarchive/libarchive/tree/7219b0134d771dc4b51bf86b4d01761b87398b1b/libarchive/test

| Local name | Upstream uuencoded fixture |
| --- | --- |
| rar4.rar | test_read_format_rar.rar.uu (includes a symlink; expected rejection) |
| rar4-compressed.rar | First compressed file from test_read_format_rar_compress_normal.rar.uu, with a new end header |
| rar5.rar | test_read_format_rar5_compressed.rar.uu |
| rar5-solid.rar | test_read_format_rar5_multiple_files_solid.rar.uu |
| rar5-encrypted.rar | test_read_format_rar5_encrypted.rar.uu |
| rar4-volume.rar | test_read_format_rar_multivolume.part0001.rar.uu |
| rar5-volume.rar | test_read_format_rar5_multiarchive.part01.rar.uu |

The libarchive license and RAR-decoder attribution notices are included in
`app/src/main/assets/archive-licenses/libarchive.txt` and `rar-decoders.txt`.
Host tests use system libarchive via JNA; Android tests use the bundled JNI binary.
