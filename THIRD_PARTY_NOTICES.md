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
