#!/usr/bin/env python3
"""Safely patch the existing Cloudflare worker for AL-ATTA v1.0.21.

Requires known version markers. Updates are atomic. Running twice is safe.
No existing user or post records are altered by the patch itself.
"""
from pathlib import Path
import os
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'cloudflare-site' / 'src' / 'worker.js'

CHANGES = [
    (
        'country:u.country||"",\n      bio:u.bio||"",',
        'country:u.country||"",\n      city:u.city||"",\n      education:u.education||"",\n      work:u.work||"",\n      bio:u.bio||"",',
        'Public user details',
    ),
    (
        'country:u.country||"",\n        bio:u.bio||"",',
        'country:u.country||"",\n        city:u.city||"",\n        education:u.education||"",\n        work:u.work||"",\n        bio:u.bio||"",',
        'Private user details',
    ),
    (
        'if(typeof body.bio==="string")u.bio=body.bio.slice(0,300);',
        'if(typeof body.bio==="string")u.bio=body.bio.slice(0,300);\n'
        '      if(typeof body.country==="string")u.country=body.country.trim().slice(0,60);\n'
        '      if(typeof body.city==="string")u.city=body.city.trim().slice(0,60);\n'
        '      if(typeof body.education==="string")u.education=body.education.trim().slice(0,100);\n'
        '      if(typeof body.work==="string")u.work=body.work.trim().slice(0,100);',
        'Profile editing',
    ),
    (
        'post.reactions=post.reactions||{};\n      for(const key of allowed){',
        'post.reactions=post.reactions||{};\n'
        '      // Selecting an emoji replaces the classic heart reaction.\n'
        '      if(Array.isArray(post.likes))post.likes=post.likes.filter(x=>x!==s.username);\n'
        '      for(const key of allowed){',
        'Emoji reaction selection',
    ),
    (
        'post.likes=post.likes||[];\n      const idx=post.likes.indexOf(s.username);',
        'post.likes=post.likes||[];\n'
        '      // A normal heart replaces an existing emoji (one reaction per person).\n'
        '      if(post.reactions){\n'
        '        for(const k of Object.keys(post.reactions)){\n'
        '          if(Array.isArray(post.reactions[k]))post.reactions[k]=post.reactions[k].filter(x=>x!==s.username);\n'
        '        }\n'
        '      }\n'
        '      const idx=post.likes.indexOf(s.username);',
        'Classic heart selection',
    ),
]


def main():
    if not TARGET.is_file():
        raise RuntimeError(f'Missing worker: {TARGET.relative_to(ROOT)}')
    original = TARGET.read_text(encoding='utf-8')
    updated = original
    for before, after, label in CHANGES:
        old_count = updated.count(before)
        new_count = updated.count(after)
        if new_count == 1:
            print(f'ALREADY PRESENT: {label}')
        elif old_count == 1 and new_count == 0:
            updated = updated.replace(before, after, 1)
            print(f'PATCHED: {label}')
        else:
            raise RuntimeError(f'SAFE ABORT {label}: old={old_count}, new={new_count}; no files modified')
    if updated != original:
        fd, temp_name = tempfile.mkstemp(dir=str(TARGET.parent), prefix='worker.v121.', suffix='.tmp')
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                stream.write(updated)
            os.replace(temp_name, TARGET)
        finally:
            if os.path.exists(temp_name):
                os.remove(temp_name)
    print('PROFILE REACTIONS PATCH READY (no data migration)')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
