#!/usr/bin/env python3
"""Makes the app's extra fonts (Settings > Appearance > Font) from Google Fonts (google/fonts on GitHub).

Each font becomes two small static files, regular (400) and bold (700), cut down to the letters the
app needs (Latin, including Swedish / Bosnian / Croatian / Serbian Latin letters, and common signs).
Run by .github/workflows/fonts.yml; the files end up in app/src/main/res/font.
usage: make_fonts.py <out-dir>
"""
import os, sys, urllib.parse, urllib.request
from io import BytesIO
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

BASE = "https://raw.githubusercontent.com/google/fonts/main/"
# id: one variable file, or (regular, bold) static files. All SIL Open Font License.
FONTS = {
	"roboto": "ofl/roboto/Roboto[wdth,wght].ttf",
	"opensans": "ofl/opensans/OpenSans[wdth,wght].ttf",
	"montserrat": "ofl/montserrat/Montserrat[wght].ttf",
	"lato": ("ofl/lato/Lato-Regular.ttf", "ofl/lato/Lato-Bold.ttf"),
	"poppins": ("ofl/poppins/Poppins-Regular.ttf", "ofl/poppins/Poppins-Bold.ttf"),
	"inter": "ofl/inter/Inter[opsz,wght].ttf",
	"oswald": "ofl/oswald/Oswald[wght].ttf",
	"raleway": "ofl/raleway/Raleway[wght].ttf",
	"nunito": "ofl/nunito/Nunito[wght].ttf",
	"playfair": "ofl/playfairdisplay/PlayfairDisplay[wght].ttf",
}
LICENSES = {
	"roboto": "ofl/roboto/OFL.txt", "opensans": "ofl/opensans/OFL.txt", "montserrat": "ofl/montserrat/OFL.txt",
	"lato": "ofl/lato/OFL.txt", "poppins": "ofl/poppins/OFL.txt", "inter": "ofl/inter/OFL.txt",
	"oswald": "ofl/oswald/OFL.txt", "raleway": "ofl/raleway/OFL.txt", "nunito": "ofl/nunito/OFL.txt",
	"playfair": "ofl/playfairdisplay/OFL.txt",
}
# Basic Latin, Latin-1, Latin Extended-A/B (č ć š ž đ å ä ö …), general punctuation, € and a few signs.
UNICODES = (
	list(range(0x20, 0x7F)) + list(range(0xA0, 0x250)) + list(range(0x2010, 0x2028)) +
	list(range(0x2030, 0x203B)) + [0x20AC, 0x2122, 0x2190, 0x2191, 0x2192, 0x2193, 0x2605, 0x2713, 0x2212]
)


def fetch(path: str) -> bytes:
	url = BASE + urllib.parse.quote(path)
	with urllib.request.urlopen(url, timeout=60) as response:
		return response.read()


def static(data: bytes, weight: int) -> TTFont:
	font = TTFont(BytesIO(data))
	if "fvar" in font:
		limits = {}
		for axis in font["fvar"].axes:
			if axis.axisTag == "wght":
				limits["wght"] = max(axis.minValue, min(axis.maxValue, weight))
			else:
				limits[axis.axisTag] = axis.defaultValue
		font = instancer.instantiateVariableFont(font, limits, updateFontNames=False)
	return font


def small(font: TTFont) -> TTFont:
	options = subset.Options()
	options.layout_features = ["*"]
	options.name_IDs = ["*"]
	options.notdef_outline = True
	options.glyph_names = False
	options.hinting = False
	subsetter = subset.Subsetter(options)
	subsetter.populate(unicodes=UNICODES)
	subsetter.subset(font)
	return font


def main(out: str) -> None:
	os.makedirs(out, exist_ok=True)
	for name, source in FONTS.items():
		regular, bold = (fetch(source[0]), fetch(source[1])) if isinstance(source, tuple) else (fetch(source),) * 2
		for weight, data, suffix in ((400, regular, "regular"), (700, bold, "bold")):
			font = small(static(data, weight))
			path = os.path.join(out, f"font_{name}_{suffix}.ttf")
			font.save(path)
			print(f"{path}: {os.path.getsize(path) // 1024} KB")
		with open(os.path.join(out, f"OFL_{name}.txt"), "wb") as handle:
			handle.write(fetch(LICENSES[name]))


if __name__ == "__main__":
	main(sys.argv[1] if len(sys.argv) > 1 else "fonts-out")
