#!/usr/bin/env python3
"""Generate launcher resources and review sheets from the unchanged Inkscape SVG.
Requires Python 3 and Inkscape. No Android build or device access.
"""
import hashlib
import math
from pathlib import Path
import random
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parent.parent
SVG = '{http://www.w3.org/2000/svg}'
RES = ROOT / 'app/src/main/res'
NUMBER = r'[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?'


def matrix(text):
    # The supplied source uses only positive uniform scale and translation.
    a, x, y = 1., 0., 0.
    for command, args in re.findall(r'(matrix|translate)\(([^)]+)\)', text or ''):
        values = list(map(float, re.findall(NUMBER, args)))
        if command == 'matrix':
            sa, b, c, d, tx, ty = values
            if b != 0 or c != 0 or sa != d or sa <= 0:
                raise ValueError('Review non-uniform/skewed SVG transform before conversion')
        else:
            sa, tx, ty = 1., values[0], values[1] if len(values) > 1 else 0.
        a, x, y = a * sa, x + a * tx, y + a * ty
    if text and not re.fullmatch(r'\s*(?:(?:matrix|translate)\([^)]+\)\s*)+', text):
        raise ValueError(f'Unsupported transform: {text}')
    return a, x, y


def outline(data):
    """Sample source cubic outlines only for fitting; exported paths stay exact."""
    tokens = re.findall(r'[A-Za-z]|' + NUMBER, data)
    i, current, start, command = 0, (0., 0.), (0., 0.), None
    points = []
    arity = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6}
    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]; i += 1
            if command.upper() == 'Z':
                current = start; points.append(current); command = None; continue
        if command is None or command.upper() not in arity:
            raise ValueError(f'Unsupported path command: {command}')
        op = command.upper(); count = arity[op]
        values = list(map(float, tokens[i:i + count])); i += count
        x, y = current
        def point(j):
            return (values[j] + (x if command.islower() else 0),
                    values[j + 1] + (y if command.islower() else 0))
        if op == 'C':
            p1, p2, end = point(0), point(2), point(4)
            for step in range(1, 129):
                t = step / 128; u = 1 - t
                points.append((u**3*x + 3*u*u*t*p1[0] + 3*u*t*t*p2[0] + t**3*end[0],
                               u**3*y + 3*u*u*t*p1[1] + 3*u*t*t*p2[1] + t**3*end[1]))
        elif op == 'H': end = (values[0] + (x if command.islower() else 0), y)
        elif op == 'V': end = (x, values[0] + (y if command.islower() else 0))
        else: end = point(0)
        current = end; points.append(end)
        if op == 'M': start = end; command = 'l' if command.islower() else 'L'
    return points


def hull(points):
    def cross(o, a, b): return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    ordered = sorted(set(points)); chains = []
    for seq in (ordered, list(reversed(ordered))):
        chain = []
        for p in seq:
            while len(chain) >= 2 and cross(chain[-2], chain[-1], p) <= 0: chain.pop()
            chain.append(p)
        chains.append(chain[:-1])
    return chains[0] + chains[1]


def enclosing_circle(points):
    """Smallest enclosing circle on the sampled convex hull, deterministic order."""
    points = hull(points); random.Random(0).shuffle(points)
    def outside(p, c): return c is None or math.hypot(p[0]-c[0], p[1]-c[1]) > c[2] + 1e-8
    def diameter(a, b): return ((a[0]+b[0])/2, (a[1]+b[1])/2, math.dist(a,b)/2)
    def three(a, b, c):
        bx, by, cx, cy = b[0]-a[0], b[1]-a[1], c[0]-a[0], c[1]-a[1]
        d = 2*(bx*cy-by*cx)
        if abs(d) < 1e-12: return max((diameter(a,b),diameter(a,c),diameter(b,c)),key=lambda c:c[2])
        x=((bx*bx+by*by)*cy-(cx*cx+cy*cy)*by)/d
        y=((cx*cx+cy*cy)*bx-(bx*bx+by*by)*cx)/d
        return a[0]+x,a[1]+y,math.hypot(x,y)
    circle = None
    for i,p in enumerate(points):
        if outside(p,circle):
            circle=(*p,0.)
            for j,q in enumerate(points[:i]):
                if outside(q,circle):
                    circle=diameter(p,q)
                    for s in points[:j]:
                        if outside(s,circle): circle=three(p,q,s)
    if any(outside(p,circle) for p in points): raise ValueError('Invalid fit')
    return circle


def write(path, content):
    path.parent.mkdir(parents=True,exist_ok=True); path.write_text(content)


def export(source, target, width):
    target.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run(['inkscape',str(source),'--export-type=png',f'--export-width={width}',
                    '--export-background-opacity=0',f'--export-filename={target}'],
                   check=True,capture_output=True)


def main():
    source = ROOT/'assets/Logo.svg'; original = source.read_bytes()
    paths, points = [], []
    def visit(node,parent=(1.,0.,0.)):
        a,x,y=matrix(node.get('transform')); pa,px,py=parent
        transform=(pa*a,px+pa*x,py+pa*y)
        if node.tag==SVG+'path':
            data=node.attrib['d']; paths.append((transform,data))
            sa,tx,ty=transform; points.extend((sa*x+tx,sa*y+ty) for x,y in outline(data))
        for child in node: visit(child,transform)
    visit(ET.fromstring(original))
    cx,cy,radius=enclosing_circle(points)
    scale=32.8/radius  # 0.2dp safety allowance for sampling and rasterization.
    fitted=[]
    for (a,x,y),data in paths:
        fitted.append(((scale*a,54+scale*(x-cx),54+scale*(y-cy)),data))
    for name,color in [('ic_launcher_foreground','#000000'),('ic_launcher_monochrome','#FFFFFF')]:
        groups=[]
        for (a,x,y),data in fitted:
            groups.append(f'  <group android:scaleX="{a:.10f}" android:scaleY="{a:.10f}" android:translateX="{x:.10f}" android:translateY="{y:.10f}">\n'
                          f'    <path android:fillColor="{color}" android:pathData={quoteattr(data)}/>\n  </group>')
        write(RES/'drawable'/f'{name}.xml','<?xml version="1.0" encoding="utf-8"?>\n'
              '<!-- Generated from assets/Logo.svg; see scripts/generate-launcher-icons.py. -->\n'
              '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
              +'\n'.join(groups)+'\n</vector>\n')
    for version in [26,33]:
        mono='  <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n' if version==33 else ''
        write(RES/f'mipmap-anydpi-v{version}/ic_launcher.xml','<?xml version="1.0" encoding="utf-8"?>\n'
              '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
              '  <background android:drawable="@color/launcher_background" />\n'
              '  <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'+mono+'</adaptive-icon>\n')
    def symbols(color):
        return ''.join(f'<g transform="matrix({a:.10f},0,0,{a:.10f},{x:.10f},{y:.10f})"><path fill="{color}" d={quoteattr(data)}/></g>' for (a,x,y),data in fitted)
    def icon(shape='round',foreground='#000000',background='#00FF00'):
        mask=('<circle cx="54" cy="54" r="36"/>' if shape=='circle' else
              '<rect x="18" y="18" width="72" height="72" rx="14.4"/>')
        return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs><clipPath id="mask">'+mask+'</clipPath></defs><g clip-path="url(#mask)"><rect width="108" height="108" fill="'+background+'"/>'+symbols(foreground)+'</g></svg>'
    with tempfile.TemporaryDirectory(prefix='openmetronome-icons-') as temporary:
        p=Path(temporary)/'legacy.svg'; p.write_text(icon())
        for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
            export(p,RES/f'mipmap-{density}/ic_launcher.png',size)
        cells=[]
        for row,(label,shape,fg,bg) in enumerate([
            ('Koło','circle','#000000','#00FF00'),('Zaokrąglony kwadrat','round','#000000','#00FF00'),
            ('Motyw jasny (przykład)','circle','#49454F','#E7E0EC'),('Motyw ciemny (przykład)','circle','#EADDFF','#4A4458')]):
            for col,surround in enumerate(['#F5F5F5','#161C20']):
                x,y=col*400,row*126
                cells.append(f'<rect x="{x}" y="{y}" width="400" height="126" fill="{surround}"/>')
                text='#171717' if col==0 else '#F5F5F5'
                cells.append(f'<text x="{x+16}" y="{y+22}" fill="{text}" font-family="sans-serif" font-size="14">{label}</text>')
                for offset,size in [(24,48),(136,72)]:
                    # Inline unique clip IDs so all cells can coexist in one SVG document.
                    svg=icon(shape,fg,bg).replace('id="mask"',f'id="m{row}-{col}-{size}"').replace('url(#mask)',f'url(#m{row}-{col}-{size})')
                    svg=svg.replace('viewBox=',f'x="{x+offset}" y="{y+34}" width="{size}" height="{size}" viewBox=',1)
                    cells.append(svg)
                    cells.append(f'<text x="{x+offset+size+8}" y="{y+65}" fill="{text}" font-family="sans-serif" font-size="12">{size}px</text>')
        sheet=Path(temporary)/'sheet.svg'
        sheet.write_text('<svg xmlns="http://www.w3.org/2000/svg" width="800" height="504" viewBox="0 0 800 504">'+''.join(cells)+'</svg>')
        export(sheet,ROOT/'docs/images/launcher-icon-preview.png',800)
    if source.read_bytes()!=original: raise ValueError('Source was modified')
    print(f'Source SHA256: {hashlib.sha256(original).hexdigest()}')
    print(f'Fit: center=({cx:.8f},{cy:.8f}), source radius={radius:.8f}, scale={scale:.10f}; target radius=32.8dp')
    print('Generated adaptive v26/v33, separate monochrome, 5 legacy densities and 48/72px review sheet.')


if __name__=='__main__': main()
