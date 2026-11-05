# LivePix

An Android gallery that understands photos taken on an iPhone. Live Photos, Portrait depth and
Apple's photo metadata keep working after the files move to an Android phone, and the interface
follows the iPhone Photos app.

## What it does

**Live Photos**
- Pairs each still (`IMG_1234.HEIC`) with its movie (`IMG_1234.MOV`). Files renamed in
  transit still pair by Apple's content identifier, which iPhone writes into both files.
- Press and hold to play with sound; the photo also moves briefly when you swipe to it.
- LIVE menu: Live, Loop, Bounce, Long Exposure (frames aligned for hand shake, then averaged), Off.
- Edit → Live: drag through the movie and tap **Make Key Photo**; toggle LIVE and sound.
- **Export as Motion Photo** turns a Live Photo into Google's format, which Google Photos and
  most Android galleries play.

**Portrait**
- Reads the depth map and portrait matte iPhone stores inside the HEIC, and renders the blur
  itself. The file keeps the sharp original, so Portrait can be turned off without loss.
- Edit → Portrait: PORTRAIT on/off, ƒ1.4–ƒ16 depth dial, tap to refocus, and Natural / Studio /
  Contour / Stage / Stage Mono / High-Key Mono lighting with an intensity dial.

**Gallery**
- Library (Years, Months, All, pinch to change grid density), Collections (pinned, albums,
  media types), Search, info sheet with camera and exposure details, share, save a copy, delete.

Edits are stored in the app and never written into your original files. "Save a Copy" writes an
edited JPEG (with the original Exif) to `Pictures/LivePix`.

## Getting photos from the iPhone

The transfer method decides what survives. LivePix needs the original HEIC (for depth) and the
MOV (for Live).

| Method | Keeps Live movie | Keeps depth |
|---|---|---|
| AirDrop ↔ Quick Share, with **Options → All Photos Data** on in the iPhone share sheet | Likely (not yet verified) | Likely (not yet verified) |
| USB to a Mac/PC (Image Capture, Windows Photos), then copy to the phone | Yes | Yes |
| Mac Photos → File → Export Unmodified Original | Yes | Yes |
| WhatsApp, Instagram, most messaging apps | No | No |

Copy the files anywhere in the phone's storage (for example `DCIM/iPhone`); LivePix finds them
through Android's media library.

## Install

Requires Android 11 or later.

```sh
./gradlew :app:assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key, which is fine for installing on your own
phone but not for publishing.

## Build and test

- JDK 17, Android SDK platform 37, NDK 27.1, CMake 3.22.
- `./gradlew :app:testDebugUnitTest` runs the parser, alignment and XMP tests.
- `./gradlew :app:connectedDebugAndroidTest` decodes real depth maps on a device or emulator.

Tests that need real iPhone files read them from `samples/` (git-ignored, so your photos never
enter the repository) and skip when it is empty.

`tools/heif_inspect.py` and `tools/makernote_cid.py` inspect HEIC structure and Live content
identifiers on a Mac.

## How it works

- `media/HeifParser.kt` reads the HEIF `meta` box: items, locations, rotation, and auxiliary
  images such as `urn:mpeg:hevc:2015:auxid:2` (depth) and Apple's portrait matte.
- Depth maps and mattes are monochrome HEVC, which Android's decoders reject, so they are decoded
  with [libde265](https://github.com/strukturag/libde265) (vendored in `app/src/main/cpp`,
  LGPL-3.0) through a small JNI bridge. The same path decodes whole HEICs on devices whose
  platform HEIF decoder fails.
- `media/PortraitRenderer.kt` blurs by depth using several blur levels. In-focus pixels are
  weighted out of the blurred layers so the subject doesn't leave a halo.
- `data/MediaRepository.kt` and `data/TraitScanner.kt` build the library and pair Live Photos;
  scan results are cached so each file is read once.

## Known limitations

- Portrait lighting and blur are approximations of Apple's rendering, not the same algorithm.
- iPhone uses SF Pro and real backdrop blur for its glass controls. Android can't ship SF Pro,
  so the app uses the system font, and its glass is a translucent fill.
- Edits made in Apple Photos (stored in `.AAE` files) are not read.
- Developed and tested on an Android 15 emulator with real iPhone 14 Pro, 16 Pro and 17 photos.
  Live Photo tests used generated movies, because none of the sample folders contained an
  intact HEIC + MOV pair. Not yet run on a physical Android phone.
- What the AirDrop ↔ Quick Share bridge delivers has not been checked with a real transfer.
- libde265 is statically linked. That's fine for personal use; distributing the app would need
  LGPL-3.0 compliance (for example, linking it as a separate shared library).
