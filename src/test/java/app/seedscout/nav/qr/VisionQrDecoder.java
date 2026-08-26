package app.seedscout.nav.qr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shells out to {@code tools/decode_qr.swift}, a small independent QR decoder built on macOS's
 * Vision framework (see that file's header for why independence from {@code QrCode.java}
 * matters here). This class exists only so {@link QrCodeScanRoundTripTest} has one place that
 * knows how to run it and interpret its exit codes.
 */
final class VisionQrDecoder {

    /** Exit code meaning: image loaded, Vision ran, but found no barcode in it. */
    static final int EXIT_NOT_FOUND = 1;

    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final Path scriptPath;

    VisionQrDecoder(Path repoRoot) {
        this.scriptPath = repoRoot.resolve("tools/decode_qr.swift");
    }

    static boolean isAvailable() {
        return isOnPath("swift");
    }

    private static boolean isOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Paths.get(dir).resolve(executable))) {
                return true;
            }
        }
        return false;
    }

    /** Result of one decode attempt: the process exit code and its stdout, line by line. */
    record Result(int exitCode, List<String> payloadLines) {
        boolean found() {
            return exitCode == 0 && !payloadLines.isEmpty();
        }
    }

    Result decode(Path pngFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("swift", scriptPath.toString(), pngFile.toString());
        pb.redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();

        // decode_qr.swift prefixes each real payload with "PAYLOAD:" specifically because the
        // Vision/CoreML backend it sits on has been observed to write its own diagnostic noise
        // (e.g. an "E5RT encountered an STL exception..." line) onto the same stream with no
        // newline of its own, which otherwise runs straight into a payload line with no
        // delimiter. Extract only the text after the marker, ignoring anything else on the
        // stream, rather than trusting every line is a clean payload.
        final String marker = "PAYLOAD:";
        List<String> lines = new ArrayList<>();
        try (var reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.lastIndexOf(marker);
                if (idx >= 0) {
                    lines.add(line.substring(idx + marker.length()));
                }
            }
        }

        boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "decode_qr.swift did not finish within " + TIMEOUT + "; treat as environment "
                            + "failure, not a decode result");
        }
        return new Result(process.exitValue(), lines);
    }
}
