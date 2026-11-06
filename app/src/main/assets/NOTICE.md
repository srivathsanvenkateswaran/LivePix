# Third-party notices

LivePix is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)). It
includes or depends on the following components.

| Component | License | Use |
|---|---|---|
| [libde265](https://github.com/strukturag/libde265) 1.1.3, by struktur AG | LGPL-3.0 (full text in `app/src/main/cpp/libde265/COPYING`) | HEVC decoding of depth maps, mattes and HEIC images; source included in `app/src/main/cpp/libde265` |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Activity, Compose, Lifecycle, ExifInterface, Core) | Apache-2.0 | App framework and UI |
| [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer) | Apache-2.0 | Live Photo and video playback |
| [Kotlin](https://kotlinlang.org) standard library and coroutines | Apache-2.0 | Language runtime |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime), by Microsoft | MIT | Runs the models below on the device |
| [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet), by Shiqi Yu | MIT | Face detection (`assets/models/yunet.onnx`, unmodified) |
| [SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface), from the OpenCV Zoo | Apache-2.0 | Face recognition (`assets/models/sface_int8.onnx`, unmodified) |
| [MobileNetV2](https://github.com/onnx/models/tree/main/validated/vision/classification/mobilenet), from the ONNX Model Zoo | Apache-2.0 | Scene and pet recognition (`assets/models/mobilenet_int8.onnx`, unmodified) |
| [GeoNames](https://www.geonames.org) | CC BY 4.0 | Place names; reduced to `assets/geo/cities.txt` by `tools/build_geo_assets.py` |
| [Natural Earth](https://www.naturalearthdata.com) | Public domain | Coastlines; converted to `assets/geo/land.bin` by the same tool |

The libde265 source is distributed unmodified, apart from the omission of its fuzzing,
sherlock265, dec265 and extra directories, so the LGPL-3.0 relinking terms can be met by
building LivePix from this repository.

ONNX Runtime and YuNet are distributed under the MIT License:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions: The above copyright notice and this
> permission notice shall be included in all copies or substantial portions of the Software.
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
> OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
> CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Copyright (c) Microsoft Corporation (ONNX Runtime). Copyright (c) 2020 Shiqi Yu (YuNet).

A copy of this file ships inside the app as `assets/NOTICE.md`.
