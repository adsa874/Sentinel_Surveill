#!/usr/bin/env python3
"""Generate PWA icons from SVG base."""

import os
from pathlib import Path

# SVG icon template (eye/surveillance icon)
SVG_TEMPLATE = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="{size}" height="{size}">
  <rect width="{size}" height="{size}" rx="{radius}" fill="#2563eb"/>
  <g transform="translate({offset}, {offset}) scale({scale})">
    <path fill="white" d="M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"/>
  </g>
</svg>'''

def generate_svg_icon(size: int, output_path: Path):
    """Generate an SVG icon at the specified size."""
    radius = size * 0.2  # 20% border radius
    padding = size * 0.2  # 20% padding
    scale = (size - 2 * padding) / 24  # Scale factor (original viewBox is 24x24)
    offset = padding

    svg_content = SVG_TEMPLATE.format(
        size=size,
        radius=radius,
        scale=scale,
        offset=offset
    )

    output_path.write_text(svg_content)
    print(f"Generated: {output_path}")

def main():
    """Generate all required icon sizes."""
    sizes = [32, 72, 96, 128, 144, 152, 192, 384, 512]
    output_dir = Path(__file__).parent

    for size in sizes:
        output_path = output_dir / f"icon-{size}.svg"
        generate_svg_icon(size, output_path)

    print("\nSVG icons generated successfully!")
    print("\nTo convert to PNG, you can use ImageMagick:")
    print("  for f in *.svg; do convert $f ${f%.svg}.png; done")
    print("\nOr use an online tool like https://svgtopng.com/")

if __name__ == "__main__":
    main()
