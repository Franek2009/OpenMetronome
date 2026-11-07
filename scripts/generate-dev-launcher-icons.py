#!/usr/bin/env python3
"""DEV-only badge over the existing launcher geometry; never edits the user's SVG."""
import copy
import re
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / 'app/src/main/res'
DEV = ROOT / 'app/src/dev/res'
ANDROID = 'http://schemas.android.com/apk/res/android'
A = '{' + ANDROID + '}'
ET.register_namespace('android', ANDROID)
RECT = 'M30,58 H78 V76 H30 Z'
# Four non-overlapping outside rectangles. Android's VectorDrawable clip-path
# does not honor fillType consistently; an even-odd rectangle hole leaves some
# original artwork under the transparent themed letters on API 26/35.
OUTSIDE_BADGE = ('M0,0 H108 V58 H0 Z M0,58 H30 V76 H0 Z '
                 'M78,58 H108 V76 H78 Z M0,76 H108 V108 H0 Z')
LETTERS = ('M35,62 H40 Q46,62 46,67 Q46,72 40,72 H35 Z '
           'M38,64.5 V69.5 H40 Q43,69.5 43,67 Q43,64.5 40,64.5 Z '
           'M48,62 H58 V64.5 H51 V65.8 H57 V68.2 H51 V69.5 H58 V72 H48 Z '
           'M60,62 H63 L66.5,69 L70,62 H73 L68,72 H65 Z')
AMBER = '#FFB300'


def export(source, target, size):
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['inkscape', str(source), '--export-type=png', f'--export-width={size}',
                    '--export-background-opacity=0', f'--export-filename={target}'], check=True, capture_output=True)


def read_foreground(path):
    # Fixed, repository-owned VectorDrawable only. Bound input and forbid DTDs/entities
    # before parsing; no extra parser dependency or external resource resolution.
    with path.open('rb') as source:
        data = source.read(128 * 1024 + 1)
    if len(data) > 128 * 1024:
        raise ValueError('Foreground XML exceeds the input limit')
    text = data.decode('utf-8-sig')
    if re.search(r'<!\s*(DOCTYPE|ENTITY)\b', text, re.IGNORECASE):
        raise ValueError('DTDs and entity declarations are not allowed')
    root = ET.fromstring(text)
    if root.tag != 'vector':
        raise ValueError('Expected the repository VectorDrawable')
    return root


def main():
    original = read_foreground(MAIN / 'drawable/ic_launcher_foreground.xml')
    for name, monochrome in [('ic_launcher_foreground', False), ('ic_launcher_monochrome', True)]:
        tree = copy.deepcopy(original)
        # Clip both layers identically to preserve edge rasterization as well as
        # geometry. The color badge is opaque; the themed letters are cutouts.
        content = ET.Element('group')
        ET.SubElement(content, 'clip-path', {A+'pathData': OUTSIDE_BADGE})
        for child in list(tree):
            tree.remove(child)
            if monochrome:
                for path in child.iter('path'): path.set(A+'fillColor', '#FFFFFF')
            content.append(child)
        tree.append(content)
        if monochrome:
            ET.SubElement(tree, 'path', {A+'fillColor': '#FFFFFF', A+'fillType': 'evenOdd', A+'pathData': RECT+' '+LETTERS})
        else:
            ET.SubElement(tree, 'path', {A+'fillColor': AMBER, A+'pathData': RECT})
            ET.SubElement(tree, 'path', {A+'fillColor': '#000000', A+'fillType': 'evenOdd', A+'pathData': LETTERS})
        target = DEV / 'drawable' / (name+'.xml')
        target.parent.mkdir(parents=True, exist_ok=True)
        ET.indent(tree)
        target.write_text('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated DEV-only badge; see scripts/generate-dev-launcher-icons.py. -->\n'+ET.tostring(tree, encoding='unicode')+'\n')

    def symbols(color):
        pieces = []
        for group in original:
            transform = f'matrix({group.get(A+"scaleX")},0,0,{group.get(A+"scaleY")},{group.get(A+"translateX")},{group.get(A+"translateY")})'
            for path in group:
                pieces.append(f'<g transform="{transform}"><path fill="{color}" d="{path.get(A+"pathData")}"/></g>')
        return ''.join(pieces)

    def icon(token, dev=True, themed=False, circle=False, dark=False):
        foreground = '#EADDFF' if dark else '#49454F'
        background = '#4A4458' if dark else '#E7E0EC'
        if not themed: foreground, background = '#000000', '#00FF00'
        mask = '<circle cx="54" cy="54" r="36"/>' if circle else '<rect x="18" y="18" width="72" height="72" rx="14.4"/>'
        ink = symbols(foreground)
        badge = ''
        definitions = f'<clipPath id="mask{token}">{mask}</clipPath>'
        if dev:
            if themed:
                definitions += f'<clipPath id="cut{token}"><path d="{OUTSIDE_BADGE}"/></clipPath>'
                ink = f'<g clip-path="url(#cut{token})">{ink}</g>'
                badge = f'<path fill="{foreground}" fill-rule="evenodd" d="{RECT} {LETTERS}"/>'
            else:
                badge = f'<path fill="{AMBER}" d="{RECT}"/><path fill="#000000" fill-rule="evenodd" d="{LETTERS}"/>'
        return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs>{definitions}</defs><g clip-path="url(#mask{token})"><rect width="108" height="108" fill="{background}"/>{ink}{badge}</g></svg>'

    with tempfile.TemporaryDirectory(prefix='openmetronome-dev-icon-') as temporary:
        path = Path(temporary)/'icon.svg'
        path.write_text(icon('legacy'))
        for density, size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
            export(path, DEV/f'mipmap-{density}/ic_launcher.png', size)
        cells = []
        for row, (label, dev, themed, circle, dark) in enumerate([
            ('Stable',False,False,False,False), ('DEV',True,False,False,False),
            ('DEV — koło',True,False,True,False), ('DEV — motyw jasny',True,True,True,False),
            ('DEV — motyw ciemny',True,True,True,True)]):
            y = row*110
            cells.append(f'<text x="12" y="{y+24}" fill="#eeeeee" font-family="sans-serif" font-size="16">{label}</text>')
            for x, size in [(220,48),(320,72)]:
                svg = icon(f'{row}{size}',dev,themed,circle,dark).replace('viewBox=',f'x="{x}" y="{y+26}" width="{size}" height="{size}" viewBox=',1)
                cells.append(svg)
        path.write_text('<svg xmlns="http://www.w3.org/2000/svg" width="440" height="550" viewBox="0 0 440 550"><rect width="440" height="550" fill="#161c20"/>'+''.join(cells)+'</svg>')
        export(path, ROOT/'docs/images/dev-launcher-preview.png',440)


if __name__ == '__main__': main()
