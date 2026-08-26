// Independent QR decoder used ONLY as a test oracle for QrCode.java's round-trip test
// (src/test/java/app/seedscout/nav/qr/QrCodeScanRoundTripTest.java).
//
// This is deliberately NOT part of the mod build. It is a tiny CLI wrapper around macOS's
// Vision framework (VNDetectBarcodesRequest), which ships its own QR decoder implementation
// with no relationship whatsoever to QrCode.java's encoder. That independence is the entire
// point: QrCode.java could pass a test against itself and still emit a symbol no real phone
// camera can read, so the oracle here has to come from a different codebase.
//
// Usage: swift tools/decode_qr.swift <path-to-png>
//
// Exit codes:
//   0  - at least one barcode payload was decoded; each payload is printed on its own stdout
//        line (there should only ever be exactly one for the images this test renders)
//   1  - the image loaded and Vision ran, but no barcode was found in it (the symbol is
//        malformed/unreadable, or not a barcode at all)
//   2  - usage error (wrong argument count)
//   3  - could not load the image at the given path
//   4  - the Vision request itself failed to run (unexpected on macOS; treat as environment
//        failure, not a decode failure)
//
// Requires macOS with the Vision framework available (Vision.framework ships with the OS,
// nothing to install). If `swift`/`swiftc` or Vision is unavailable, the JUnit test that
// shells out to this script skips itself via Assumptions rather than failing the build.

import Foundation
import ImageIO
import Vision

let arguments = CommandLine.arguments
guard arguments.count == 2 else {
    FileHandle.standardError.write("usage: decode_qr.swift <path-to-png>\n".data(using: .utf8)!)
    exit(2)
}

let path = arguments[1]
let url = URL(fileURLWithPath: path)

guard let imageSource = CGImageSourceCreateWithURL(url as CFURL, nil),
      let cgImage = CGImageSourceCreateImageAtIndex(imageSource, 0, nil) else {
    FileHandle.standardError.write("could not load image at \(path)\n".data(using: .utf8)!)
    exit(3)
}

let request = VNDetectBarcodesRequest()
request.symbologies = [.qr]

let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
do {
    try handler.perform([request])
} catch {
    FileHandle.standardError.write("Vision request failed: \(error)\n".data(using: .utf8)!)
    exit(4)
}

guard let results = request.results, !results.isEmpty else {
    exit(1)
}

// Prefixed and on its own explicit line: Apple's CoreML/Espresso backend that Vision's
// barcode detector sits on top of sometimes writes its own diagnostic noise (observed:
// "E5RT encountered an STL exception...") to the SAME stream this process writes to, with
// no trailing newline of its own, which otherwise runs it straight into this payload line
// with no delimiter. The prefix lets the caller unambiguously pick the real payload lines
// out of anything else that lands on stdout, rather than trusting the stream is clean.
for observation in results {
    if let payload = observation.payloadStringValue {
        print("PAYLOAD:" + payload)
    }
}
