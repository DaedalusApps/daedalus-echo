from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

NAVY = (26, 35, 126)
WHITE = (255, 255, 255)

def make_icon(size, output_path, rounded=False):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    r = size // 2 if rounded else size // 5
    # Draw background shape
    draw.rounded_rectangle([0, 0, size-1, size-1], radius=r, fill=NAVY)
    # Draw DN text
    font_size = int(size * 0.40)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", font_size)
    except:
        try:
            font = ImageFont.truetype("/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf", font_size)
        except:
            font = ImageFont.load_default()
    text = "DN"
    bbox = draw.textbbox((0, 0), text, font=font)
    tw, th = bbox[2]-bbox[0], bbox[3]-bbox[1]
    x = (size - tw) // 2 - bbox[0]
    y = (size - th) // 2 - bbox[1]
    draw.text((x, y), text, font=font, fill=WHITE)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(str(output_path), "PNG")
    print(f"Created {output_path}")

base = Path(__file__).resolve().parent.parent / "android" / "app" / "src" / "main" / "res"
sizes = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
for density, sz in sizes.items():
    make_icon(sz, base / density / "ic_launcher.png", rounded=False)
    make_icon(sz, base / density / "ic_launcher_round.png", rounded=True)
print("All icons done.")
