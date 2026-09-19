#!/usr/bin/env python3
"""Android resource/contract checks. Not a device or browser accessibility test."""
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
res = root / 'app/src/main/res'
android = '{http://schemas.android.com/apk/res/android}'
design = (root / 'DESIGN.md').read_text()
contract = (root / 'UX-CONTRACT.md').read_text()
failures = []

def check(condition, message):
    if not condition:
        failures.append(message)

def values(folder, file):
    return {e.get('name'): e.text for e in ET.parse(res / folder / file).getroot()}

colors = values('values', 'colors.xml')
for capability in ['Form','CRUD','Dialog','Status feedback','File import','Toast','Scrollbar']:
    check(bool(re.search(r'^\| '+re.escape(capability)+r' \| [^|]+ \| [^|]+ \| [^|]+ \| [^|]+ \|$', contract, re.M)), 'canonical owner missing: '+capability)
night = values('values-night', 'colors.xml')
for theme, entries in [('light', colors), ('dark', night)]:
    for key, color in entries.items():
        if key == 'on_primary':
            continue
        token = key.replace('_', '-')
        if key in {'background','surface','text','muted','border'} or (theme == 'dark' and key in {'success','warning','danger'}):
            token += '-' + theme
        check(f'{token}: "{color}"' in design, f'token drift: {theme}/{key}')

dimens = values('values', 'dimens.xml')
check(dimens['primary_height'] == '68dp', 'primary target must be 68dp')
check(dimens['button_height'] == '56dp', 'button target must be 56dp')
check(dimens['touch_target'] == '48dp', 'touch target must be 48dp')
check((res / 'layout-land/activity_main.xml').is_file(), 'landscape layout missing')
for p in [*res.glob('layout/*.xml'), *res.glob('layout-land/*.xml')]:
    source = p.read_text()
    check(not re.search(r'#[0-9A-Fa-f]{6,8}', source), f'raw color: {p.name}')
    check('可拍照' not in source, f'retired copy: {p.name}')
    elements = list(ET.parse(p).iter())
    labels = {e.get(android + 'labelFor') for e in elements if e.get(android + 'labelFor')}
    for e in elements:
        if e.tag == 'EditText':
            field = e.get(android + 'id', '').replace('@+id/', '@id/')
            check(field in labels, f'input label missing: {field}')
        if e.tag == 'Button':
            check(e.get(android + 'layout_height') in {'@dimen/button_height','@dimen/primary_height'}, f'button target: {p.name}')

sources = '\n'.join(p.read_text() for p in (root / 'app/src/main/java/com/cartunnel/client/ui').glob('*.kt'))
check('setOnTouchListener' not in sources, 'custom touch interception is forbidden')
check('可拍照' not in sources, 'retired diagnostic copy')
check('ProfileMode' not in sources and 'VlessRealityProfile' not in sources, 'retired UI model')
check('setting_diagnostics' not in sources, 'duplicate diagnostic copy entry')
print(json.dumps({'kind':'Android static resource audit', 'failures':failures,
    'deviceExecution':'NOT_EXECUTED', 'browserChecks':'NOT_APPLICABLE'}, ensure_ascii=False, indent=2))
raise SystemExit(bool(failures))
