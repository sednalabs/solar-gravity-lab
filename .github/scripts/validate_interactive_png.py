"""Validate the native image returned by an interactive Android observation."""

import base64
import binascii
import subprocess


def has_decodable_native_png(content_items: object) -> bool:
    if not isinstance(content_items, list):
        return False
    for item in content_items:
        if not isinstance(item, dict) or item.get("type") != "inputImage":
            continue
        image_url = item.get("imageUrl")
        if not isinstance(image_url, str) or not image_url.startswith("data:image/png;base64,"):
            continue
        try:
            image = base64.b64decode(image_url.partition(",")[2], validate=True)
            # ImageMagick decodes pixel data and treats malformed/truncated input
            # warnings as failures. A PNG signature alone is not image evidence.
            decoded = subprocess.run(
                ["convert", "-regard-warnings", "png:-", "null:"],
                input=image,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
                check=False,
            )
        except (ValueError, binascii.Error, OSError, subprocess.TimeoutExpired):
            continue
        if decoded.returncode == 0:
            return True
    return False
