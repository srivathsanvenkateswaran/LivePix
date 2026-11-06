# LivePix

**An Android gallery that understands photos taken on an iPhone.**

When iPhone photos land on an Android phone, Live Photos turn into a still plus a stray video,
and Portrait shots lose the controls that made them Portrait. LivePix reads what the iPhone left in
the files and gives those controls back, inside an interface modelled on the iPhone Photos app.

[![CI](https://github.com/srivathsanvenkateswaran/LivePix/actions/workflows/ci.yml/badge.svg)](https://github.com/srivathsanvenkateswaran/LivePix/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

> **Status:** 1.0 is in device testing ahead of a Play Store release. Bug reports with sample
> files are very welcome (see [Reporting problems](#reporting-problems)).

## Features

### Live Photos and Motion Photos
- Works with iPhone's HEIC + MOV pairs and with **Motion Photos**, where the movie is inside the
  file. That covers iPhone photos converted by Google Photos (`IMG_0409.MP.HEIC`) and Pixel and
  Samsung motion photos. Pairs renamed in transit are matched through the content identifier
  iPhone writes into both files.
- Press and hold to play with sound; the photo also moves briefly when you swipe to it.
- The LIVE menu offers **Live**, **Loop**, **Bounce**, **Long Exposure** and **Off**. Long Exposure
  aligns the frames to cancel hand shake before blending them.
- **Edit → Live**: scrub through the movie and tap **Make Key Photo**; toggle Live and sound.
- **Export as Motion Photo** turns an iPhone HEIC + MOV pair into Google's Motion Photo format.

### Portrait
- Reads the depth map and subject matte stored inside iPhone Portrait HEICs and renders the
  background blur on the phone. The file holds the sharp original, so Portrait can be turned off.
- **Edit → Portrait**: Portrait on/off, an ƒ1.4–ƒ16 depth dial, tap to refocus, and the six Portrait
  Lighting styles with intensity.

### Editing
- **Adjust**: Auto, Exposure, Brilliance, Highlights, Shadows, Contrast, Brightness, Black Point,
  Saturation, Vibrance, Warmth, Tint, Sharpness, Definition, Noise Reduction and Vignette.
- **Filters**: Vivid, Dramatic (each also Warm and Cool), Mono, Silvertone and Noir, with intensity.
- **Crop**: free or fixed aspect, rotate, flip, and straighten.
- **Video trim**, applied when you share or save.
- Every edit is non-destructive and can be reverted; the grid shows edited versions, as in Photos.

### Library and organisation
- Library grid with **Years / Months / All**, pinch to change density, and **swipe to select**
  (drag across photos in Select mode; the grid scrolls at the edges).
- Long-press any photo for a menu: Share, Favourite, Add to Album, Select, Hide, Delete.
- **My Albums** (create, rename, delete, add and remove photos) plus the phone's own folders.
- **Media types**: Favourites, Videos, Selfies, Live Photos, Portrait, Long Exposure, Panoramas,
  Screenshots, Animated, RAW and Recently Edited.
- **Hidden** and **Recently Deleted**, locked with your fingerprint, face or screen lock. Delete moves
  items to Android's 30-day trash, from which they can be recovered.
- **Duplicates** finds exact and near-exact copies. Merge keeps the best copy (Live or Portrait first,
  then the highest resolution), gives it the others' favourite and albums, and sends the rest to
  Recently Deleted.
- Search by name, place, person, date, camera and what's in the picture ("beach", "dog", "pizza"); an
  info panel with camera, lens, exposure and a map; video playback with a scrubber; share, save a
  copy, and light and dark mode.

### People & Pets
- Faces are found and grouped into people on the phone. Name someone, combine two groups by giving
  them the same name, and use **Not This Person** to correct a photo.
- **Dogs** and **Cats** collections. These group by species; individual pets aren't told apart.
- Stills only for now: faces in videos aren't indexed.
- The first run looks through every photo. It works while LivePix is open, pauses in the background
  and in battery saver, and picks up where it left off.

### Memories and Slideshow
- **Memories** are made from trips (named after where you went), this day in past years, named
  people, recent months, past years and pets.
- **Slideshow** plays any album, selection or memory with Ken Burns, Dissolve or Slide, at three
  speeds, with music you pick from your own files. LivePix bundles no music.

### Places
- Every geotagged photo and video on a map that clusters as you zoom, plus a list of places.
- The map is drawn from data inside the app, so it works offline and needs no network access. It
  shows coastlines and city names, not roads or satellite imagery.

### Adjust Date, Time and Location
- Change them for one item or many; several items shift together and keep their spacing.
- As in Photos, originals aren't rewritten. The new values drive sorting and display, go into any
  exported copy, and can be reverted.

## Moving photos from an iPhone

What arrives on Android depends on how the photos are sent. LivePix needs the original HEIC
(Portrait depth) and the MOV (Live movie).

| Method | Live movie kept | Portrait depth kept |
|---|---|---|
| USB to a Mac or PC (Image Capture, Windows Photos), then copy to the phone | Yes | Yes |
| Mac Photos → **File → Export → Export Unmodified Original** | Yes | Yes |
| AirDrop to Quick Share, with **Options → All Photos Data** on in the iPhone share sheet | Not yet confirmed | Not yet confirmed |
| WhatsApp, Instagram and most messaging apps | No | No |

Put the files anywhere in the phone's storage, for example `DCIM/iPhone`. LivePix finds them
through Android's media library.

## Install

LivePix needs Android 11 or later. Until the Play Store release, download the APK from
[Releases](https://github.com/srivathsanvenkateswaran/LivePix/releases) or build it yourself.

## Building

Requirements: JDK 17, Android SDK platform 37, NDK 27.1.12297006 and CMake 3.22.1.

```sh
git clone https://github.com/srivathsanvenkateswaran/LivePix.git
cd LivePix
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM tests
./gradlew :app:connectedDebugAndroidTest   # on a device or emulator
```

Tests that need real iPhone files read them from `samples/`, which is git-ignored so personal
photos never enter the repository. Without samples, those tests are skipped. The expected names
are in `ParsersTest.kt` and `DepthDecodeTest.kt`.

A signed App Bundle needs an upload key, supplied through the environment. Without these variables,
release builds fall back to the debug key so they stay installable:

```sh
LIVEPIX_KEYSTORE=/path/to/upload.jks LIVEPIX_KEYSTORE_PASSWORD=... ./gradlew :app:bundleRelease
```

`tools/build_geo_assets.py` regenerates the map and city data from its Natural Earth and GeoNames
sources. `tools/heif_inspect.py` lists the items inside a HEIC (depth, mattes, rotation), and
`tools/makernote_cid.py` prints a still's Live Photo content identifier. Both run with the stock
Python 3 on macOS or Linux.

## How it works

| Piece | What it does |
|---|---|
| `media/HeifParser.kt` | Reads the HEIF `meta` box: items, extents, rotation, references, and auxiliary images such as depth (`urn:mpeg:hevc:2015:auxid:2`) and Apple's portrait matte. |
| `cpp/depth_decoder.cpp` + [libde265](https://github.com/strukturag/libde265) | Decodes the monochrome HEVC that depth maps and mattes use, which Android's built-in decoders reject. Also decodes whole HEICs on phones whose system decoder fails. |
| `media/PortraitRenderer.kt` | Depth-of-field blur built from several blur levels. In-focus pixels are kept out of the blurred layers so the subject doesn't leave a halo. Lighting styles use the subject matte. |
| `media/AppleMetadata.kt` | Reads Live Photo content identifiers from the HEIC MakerNote and the MOV `keys` metadata. |
| `media/LiveMedia.kt`, `FrameAligner.kt` | Frame extraction for key photos and Bounce; aligned frame averaging for Long Exposure. |
| `media/Exporter.kt` | Edited JPEG export with the original Exif, and Motion Photo assembly (JPEG + MP4 + XMP). |
| `media/Vision.kt`, `FaceMath.kt` | Runs the face and scene models through ONNX Runtime. |
| `data/People.kt`, `Duplicates.kt`, `Memories.kt`, `Geo.kt` | Face clustering, copy detection, memory building and offline place names. |
| `data/` (the rest) | MediaStore scanning, Live pairing, cached per-file metadata, and stored edits. |
| `ui/` | Jetpack Compose screens styled after iOS Photos. |

## Known limitations

- Portrait blur and lighting approximate Apple's rendering; results are close but not identical.
- Apple's SF Pro font and true backdrop blur aren't available on Android, so the app uses the
  system font and translucent controls.
- Edits made in Apple Photos (stored in separate `.AAE` files) are not read.
- Face grouping was calibrated on the LFW research dataset. Real albums are harder (profile shots,
  children, poor light), so expect to correct some groups.
- Scene search and pet detection use a general ImageNet classifier, which knows 1,000 specific things
  and will miss or misname some pictures.
- Older iPhone "Most Compatible" JPEGs carry depth in a different form, which isn't supported yet.

## Reporting problems

Please open an [issue](https://github.com/srivathsanvenkateswaran/LivePix/issues) with the iPhone
model, how the photos were transferred, and, if you can share it, a sample file. Running
`tools/heif_inspect.py` on the file and pasting the output helps too.

## Privacy

LivePix works entirely on the device, including face recognition. It has no network access and
collects no data. See
[PRIVACY.md](PRIVACY.md).

## License

LivePix is free software under the [GNU General Public License v3.0](LICENSE). Third-party
components and their licenses are listed in [NOTICE.md](NOTICE.md).
