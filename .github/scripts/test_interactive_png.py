#!/usr/bin/env python3
"""Exercise the same native-image decoder used by the hosted session."""

import base64
import struct
import zlib

from validate_interactive_png import has_decodable_native_png


def chunk(kind: bytes, data: bytes) -> bytes:
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))


def response(data: bytes) -> list[dict[str, str]]:
    return [{"type": "inputImage", "imageUrl": "data:image/png;base64," + base64.b64encode(data).decode()}]


def main() -> None:
    signature = b"\x89PNG\r\n\x1a\n"
    png = (
        signature
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00"))
        + chunk(b"IEND", b"")
    )
    assert has_decodable_native_png(response(png))
    assert not has_decodable_native_png(response(signature))
    assert not has_decodable_native_png(response(png[:-12]))
    assert not has_decodable_native_png(response(signature + b"malformed"))
    assert not has_decodable_native_png([{"type": "inputImage", "imageUrl": "data:image/png;base64,%%%"}])
    assert not has_decodable_native_png([{"type": "inputText", "text": "no image"}])
    print("interactive Android native PNG decoding tests passed")


if __name__ == "__main__":
    main()
