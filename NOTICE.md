# Third-party notices

LivePix is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)). It
includes or depends on the following components.

| Component | License | Use |
|---|---|---|
| [libde265](https://github.com/strukturag/libde265) 1.1.3, by struktur AG | LGPL-3.0 (full text in `app/src/main/cpp/libde265/COPYING`) | HEVC decoding of depth maps, mattes and HEIC images; source included in `app/src/main/cpp/libde265` |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Activity, Compose, Lifecycle, ExifInterface, Core) | Apache-2.0 | App framework and UI |
| [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer) | Apache-2.0 | Live Photo and video playback |
| [Kotlin](https://kotlinlang.org) standard library and coroutines | Apache-2.0 | Language runtime |

The libde265 source is distributed unmodified, apart from the omission of its fuzzing,
sherlock265, dec265 and extra directories, so the LGPL-3.0 relinking terms can be met by
building LivePix from this repository.
