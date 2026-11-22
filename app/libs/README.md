# app/libs Directory
Place offline native/Android AAR/JAR dependencies here, such as Sherpa-ONNX Android AAR packages (`*.aar`).
Gradle automatically includes all `.jar` and `.aar` files placed under `app/libs/`.

In CI, `.github/workflows/build-apk.yml` downloads the official AAR automatically:
`https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.29/sherpa-onnx-1.12.29.aar`
Do not commit the binary AAR to the repository.
