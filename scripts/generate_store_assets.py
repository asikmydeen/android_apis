#!/usr/bin/env python3
"""
Generates 512x512 High-Res App Icon and 1024x500 Feature Graphic for Google Play Store.
"""

import os
from PIL import Image, ImageDraw, ImageFont


def create_app_icon(output_path: str) -> None:
    size = 512
    img = Image.new("RGBA", (size, size), (9, 14, 23, 255))  # #090E17
    draw = ImageDraw.Draw(img)

    # Outer Pulse Rings
    draw.ellipse([56, 56, 456, 456], fill=(56, 189, 248, 40))   # #38BDF8 alpha 40
    draw.ellipse([100, 100, 412, 412], fill=(129, 140, 248, 70)) # #818CF8 alpha 70

    # Sensor Core
    draw.ellipse([160, 160, 352, 352], fill=(56, 189, 248, 255)) # #38BDF8
    draw.ellipse([210, 210, 302, 302], fill=(16, 185, 129, 255)) # #10B981

    # Signal Arc Crosshairs
    draw.line([(56, 256), (120, 256)], fill=(56, 189, 248, 255), width=12)
    draw.line([(392, 256), (456, 256)], fill=(56, 189, 248, 255), width=12)
    draw.line([(256, 56), (256, 120)], fill=(56, 189, 248, 255), width=12)
    draw.line([(256, 392), (256, 456)], fill=(56, 189, 248, 255), width=12)

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    img.save(output_path, "PNG")
    print(f"Generated 512x512 App Icon at {output_path}")


def create_feature_graphic(output_path: str) -> None:
    w, h = 1024, 500
    img = Image.new("RGBA", (w, h), (9, 14, 23, 255))
    draw = ImageDraw.Draw(img)

    # Subtle cyan grid lines
    for x in range(0, w, 40):
        draw.line([(x, 0), (x, h)], fill=(56, 189, 248, 15), width=1)
    for y in range(0, h, 40):
        draw.line([(0, y), (w, y)], fill=(56, 189, 248, 15), width=1)

    # Decorative Sensor Core Graphic on Right
    cx, cy = 760, 250
    draw.ellipse([cx - 180, cy - 180, cx + 180, cy + 180], fill=(56, 189, 248, 30))
    draw.ellipse([cx - 120, cy - 120, cx + 120, cy + 120], fill=(129, 140, 248, 60))
    draw.ellipse([cx - 70, cy - 70, cx + 70, cy + 70], fill=(56, 189, 248, 255))
    draw.ellipse([cx - 35, cy - 35, cx + 35, cy + 35], fill=(16, 185, 129, 255))

    # Text Title & Subtitle
    try:
        font_title = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 64)
        font_sub = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 28)
        font_badge = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 22)
    except Exception:
        font_title = font_sub = font_badge = ImageFont.load_default()

    draw.text((80, 150), "SensIO", fill=(255, 255, 255, 255), font=font_title)
    draw.text((80, 230), "Physical Senses for Local AI Agents", fill=(56, 189, 248, 255), font=font_sub)
    draw.text((80, 280), "50Hz IMU · Touch API · USB Serial · REST & WebSockets", fill=(156, 163, 175, 255), font=font_badge)

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    img.save(output_path, "PNG")
    print(f"Generated 1024x500 Feature Graphic at {output_path}")


def main() -> None:
    base_dir = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "fastlane/metadata/android/en-US/images",
    )
    icon_path = os.path.join(base_dir, "icon.png")
    feature_path = os.path.join(base_dir, "featureGraphic.png")

    create_app_icon(icon_path)
    create_feature_graphic(feature_path)


if __name__ == "__main__":
    main()
