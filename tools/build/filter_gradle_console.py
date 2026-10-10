"""Stream only known Gradle console noise away; callers keep full evidence first."""
import re
import sys

# Bytes keep CRLF, unterminated lines and invalid UTF-8 diagnostics unchanged.
NOISE = re.compile(
    rb"[ \t\r\v\f]*(?:Caching disabled for task '[^']+' because:|"
    rb"Build cache is disabled|Caching has not been enabled for the task|"
    rb"Loading library manifest /.*/AndroidManifest\.xml|"
    rb"Merging library manifest /.*/AndroidManifest\.xml)[ \t\r\v\f]*"
)
TRANSFORM = re.compile(rb"Caching disabled for [A-Za-z0-9_$]+Transform: .+ because:")


def emit(destination, line):
    destination.write(line)
    destination.flush()


def filter_stream(source, destination):
    """Hold at most one transform header while waiting for its exact reason."""
    pending = None
    for line in source:
        current = line.removesuffix(b"\n").removesuffix(b"\r")
        transform = TRANSFORM.fullmatch(current) is not None
        if pending is not None:
            if current == b"  Caching not enabled.":
                pending = None
                continue
            emit(destination, pending)
            pending = None
            if not transform:
                # Alternate reasons belong to the kept block, even if normally noise.
                emit(destination, line)
                continue
        if transform:
            pending = line
        elif NOISE.fullmatch(current) is None:
            emit(destination, line)
    if pending is not None:
        emit(destination, pending)


if __name__ == "__main__":
    filter_stream(sys.stdin.buffer, sys.stdout.buffer)
