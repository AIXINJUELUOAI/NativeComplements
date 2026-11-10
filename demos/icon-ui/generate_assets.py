"""One vector geometry supplies the SVG references, Android vectors and flat PNG."""
from pathlib import Path
from PIL import Image, ImageDraw

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[1]
DRAWABLE = ROOT / 'app/src/main/res/drawable'
ASSETS = BASE / 'src/main/assets'
# Original geometric lettering; not copied from Opticon or a font.
O = 'M58,54 A14,18 0,1 1,30,54 A14,18 0,1 1,58,54 Z M52,54 A8,12 0,1 0,36,54 A8,12 0,1 0,52,54 Z'
I = 'M65,48 H71 V72 H65 Z M71.5,39.5 A3.5,3.5 0,1 1,64.5,39.5 A3.5,3.5 0,1 1,71.5,39.5 Z'


def foreground(svg=True, shifted=False):
    if svg:
        return f'<g transform="translate({6 if shifted else 0} 0)" fill="#20202a" fill-rule="evenodd"><path d="{O}"/><path d="{I}"/></g>'
    return f'<group android:translateX="{6 if shifted else 0}"><path android:fillColor="#FF20202A" android:fillType="evenOdd" android:pathData="{O}"/><path android:fillColor="#FF20202A" android:pathData="{I}"/></group>'


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    for name, body in [('foreground', foreground()), ('background', '<path fill="#fce4ec" d="M0 0H108V108H0Z"/>'),
                       ('composite', '<path fill="#fce4ec" d="M0 0H108V108H0Z"/>' + foreground())]:
        (ASSETS / (name + '.svg')).write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">{body}</svg>\n', encoding='utf-8')
    for shifted in [False, True]:
        suffix = '_shifted' if shifted else ''
        (DRAWABLE / ('icon_lab_foreground' + suffix + '.xml')).write_text(
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">'
            + foreground(False, shifted) + '</vector>\n', encoding='utf-8')
    k = 8
    fg = Image.new('RGBA', (108*k, 108*k))
    d = ImageDraw.Draw(fg)
    box = lambda b: tuple(round(n*k) for n in b)
    d.ellipse(box((30, 36, 58, 72)), fill='#20202a')
    d.ellipse(box((36, 42, 52, 66)), fill=(0, 0, 0, 0))
    d.rectangle(box((65, 48, 71, 72)), fill='#20202a')
    d.ellipse(box((64.5, 36, 71.5, 43)), fill='#20202a')
    flat = Image.new('RGBA', fg.size, '#fce4ec')
    flat.alpha_composite(fg)
    flat = flat.crop((18*k, 18*k, 90*k, 90*k)).resize((432, 432), Image.Resampling.LANCZOS)
    target = BASE / 'src/main/res/mipmap-xxxhdpi'
    target.mkdir(parents=True, exist_ok=True)
    flat.save(target / 'ic_launcher.png')
    print('Generated flat PNG, three SVG references and two Android foreground vectors')


if __name__ == '__main__':
    main()
