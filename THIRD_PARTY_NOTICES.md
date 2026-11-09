# Third-party notices

## shazamio-core

The music-recognition fingerprinting implementation in
`app/src/main/java/com/domenota/medialoader/data/recognition/ShazamSignatureGenerator.kt`
is adapted from the algorithm in shazamio-core.

Source: https://github.com/shazamio/shazamio-core
Reference implementation version reviewed: 1.2.0

MIT License

Copyright (c) 2024 dotX12

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## ONNX Runtime for Android

Microsoft ONNX Runtime — https://github.com/microsoft/onnxruntime
License: MIT. Runtime dependency used for device-local image inpainting.

## LaMa (downloaded separately, NOT bundled with APK)

Model: LaMa, Resolution-robust Large Mask Inpainting with Fourier Convolutions
Original research / weights: https://github.com/advimman/lama
ONNX export distribution: https://huggingface.co/sapienkit/LaMa-ONNX
Model file: `lama_fp32.onnx`; exact SHA-256 pinned in LaMaModelStore.kt.
License reported by model publisher: Apache-2.0.
Training dataset: Places2 (CC BY 4.0). Relevant credit belongs to Places2 authors.
The model is requested only by explicit user action; photographs and paint masks
stay on the device. Model weights are not redistributed in the application APK.


## EfficientDet-Lite0 (downloaded separately, NOT bundled with APK)

Official MediaPipe Tasks model (mandatory metadata):
https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite
Exact binary size: 13,836,895 bytes.
SHA-256: 40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58
Independent original-file mirror (Apache-2.0):
https://huggingface.co/ketiswp/mediapipe-EfficientDet-Lite0-COCO2017-fp32-onnx
License of MediaPipe runtime and source: Apache-2.0. Check the official
model's distribution terms again before final production release.
Trained on COCO 2017; some data usage rights depend on original COCO images.
ON-device CPU inference via MediaPipe Tasks Vision (Apache-2.0).
No photos are uploaded. Binary is fetched only by an explicit installation action.

## SRNet specular highlight removal (bundled offline model)

Original source and pretrained weights: https://github.com/zhiyiYo/Specular-Removal
Original copyright: Copyright (c) 2021 Huang Zhengzhi.
Original code and pretrained checkpoint distributed in the project's MIT-licensed
repository (LICENSE): https://github.com/zhiyiYo/Specular-Removal/blob/6f44a130d2b20568f1a1b1159e32a4ec6b46ef19/LICENSE
Upstream pinned commit: `6f44a130d2b20568f1a1b1159e32a4ec6b46ef19`.
Original architecture: `specular_removal/network.py`.
Original architecture SHA-256: `472103bbc1bc6e3ab6839af147e12d3edec1110d3a8da891929c7c571a5b783f`.
Original checkpoint: `model/SRNet_2021-07-23_23-00-22.pth` (259,399 bytes).
Original checkpoint SHA-256: `d20a8b1021cd150cba0fce6738fac997f9283390de134756ecc9710245c2cba8`.
Our verified ONNX conversion: `app/src/main/assets/ai/specular_srnet_256.onnx.b64`
(base64 source of binary with SHA-256 `1538f5800858c329e93576566d40f568fcc07d8fdc674bca454fbfc4771c63e4`,
230,221 bytes). Conversion CI: https://github.com/DomEnota-hub/ML/actions/runs/42581463302
The original MIT notice and permission grant apply to the original project
and derivative model packaging. See original LICENSE for full warranty disclaimer.
User explicitly installs the bundled model into private app storage; no photo
leaves the Android device. This model targets specular highlights, not objects,
glassy reflections or irrevocably clipped overexposure. Quality is not guaranteed.
