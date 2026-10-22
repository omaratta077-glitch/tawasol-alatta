#!/usr/bin/env python3
"""READ ONLY. Owner-only Cloudflare Durable Object and R2 backup/preview.
Never sends the password, session token, or backup to ChatGPT or GitHub.
"""
import argparse
from datetime import datetime, timezone
import getpass
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_ORIGIN = 'https://tawasol-alatta.omaratta077-4fd.workers.dev'


def request_json(origin, path, data):
    request = urllib.request.Request(
        origin + path, data=json.dumps(data, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json', 'Cache-Control': 'no-store'}, method='POST')
    try:
        with urllib.request.urlopen(request, timeout=90) as reply:
            result = json.load(reply)
    except urllib.error.HTTPError as exc:
        details = exc.read(1000).decode('utf-8', 'replace')
        raise RuntimeError(f'HTTP {exc.code} on {path}: {details}') from exc
    if not result.get('ok'):
        raise RuntimeError('Server rejected ' + path + ': ' + str(result.get('error')))
    return result


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description='READ ONLY: owner backup or preview; never deletes data')
    parser.add_argument('--origin', default=DEFAULT_ORIGIN,
                        help='Cloudflare Worker HTTPS origin')
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--preview', action='store_true', help='Show counts only; do not save')
    mode.add_argument('--backup', action='store_true', help='Export all DO entries and R2 media to private local folder')
    args = parser.parse_args()
    origin = args.origin.rstrip('/')
    if not origin.startswith('https://'):
        parser.error('HTTPS is required')
    print('Owner-only data audit. NO DELETE operations are implemented.')
    username = input('Admin username: ').strip().lower()
    password = getpass.getpass('Admin password (never saved): ')
    login = request_json(origin, '/api/login', {'username': username, 'password': password})
    del password
    token = login['token']
    preview = request_json(origin, '/api/admin/reset/preview', {'token': token})
    if preview.get('owner') != username:
        raise RuntimeError('Safety halt: logged-in username is not the configured Cloudflare owner.')
    public_preview = {key: val for key, val in preview.items() if key != 'token'}
    print(json.dumps(public_preview, ensure_ascii=False, indent=2))
    if args.preview:
        print('PREVIEW ONLY. Nothing was deleted.')
        return
    admin_password = getpass.getpass('Independent administration protection password (never saved): ')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    destination = Path.home() / ('AL_ATTA_PRIVATE_BACKUP_' + stamp)
    destination.mkdir(mode=0o700)
    try:
        destination.chmod(0o700)
    except OSError:
        pass
    (destination / 'preview.json').write_text(json.dumps(public_preview, ensure_ascii=False, indent=2), encoding='utf-8')
    entries_path = destination / 'durable_objects.jsonl'
    count = 0
    start_after = ''
    seen = set()
    with entries_path.open('w', encoding='utf-8') as out:
        while True:
            reply = request_json(origin, '/api/admin/reset/export',
                                 {'token': token, 'adminPassword': admin_password, 'startAfter': start_after, 'limit': 10})
            for entry in reply['entries']:
                key = entry['key']
                if key in seen:
                    raise RuntimeError('Inconsistent backup: duplicate Durable Object key ' + key)
                seen.add(key)
                out.write(json.dumps(entry, ensure_ascii=False) + '\n')
                count += 1
            new_cursor = reply.get('next')
            if not new_cursor:
                break
            if new_cursor == start_after:
                raise RuntimeError('Server returned an unchanged cursor')
            start_after = new_cursor
    if count != preview['durableObjectKeys']:
        raise RuntimeError(f'Backup incomplete/changed while exporting: {count} != {preview["durableObjectKeys"]}. Freeze writes and retry.')
    media_dir = destination / 'media'
    media_count = 0
    media_bytes = 0
    media_cursor = None
    if preview.get('media', {}).get('configured'):
        media_dir.mkdir()
        while True:
            reply = request_json(origin, '/api/admin/reset/media-list',
                                 {'token': token, **({'cursor': media_cursor} if media_cursor else {})})
            for obj in reply['objects']:
                key = obj['key']
                parts = Path(key).parts
                if not parts or any(x in ('.', '..') for x in parts) or '\\' in key or key.startswith('/') or ':' in key:
                    raise RuntimeError('Unsafe R2 object key in manifest: ' + repr(key))
                output = media_dir.joinpath(*parts)
                output.parent.mkdir(parents=True, exist_ok=True)
                url = origin + '/media/' + urllib.parse.quote(key, safe='/')
                with urllib.request.urlopen(url, timeout=180) as response, output.open('wb') as binary:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        binary.write(chunk)
                if output.stat().st_size != obj['size']:
                    raise RuntimeError('Media size mismatch for ' + key)
                media_count += 1
                media_bytes += output.stat().st_size
            cursor = reply.get('next')
            if not cursor:
                break
            if cursor == media_cursor:
                raise RuntimeError('Unchanged R2 cursor')
            media_cursor = cursor
    if preview.get('media', {}).get('configured') and (
        media_count != preview['media']['count'] or media_bytes != preview['media']['bytes']):
        raise RuntimeError('R2 media counts changed during backup; verify and repeat.')
    checksum_manifest = {}
    for file in destination.rglob('*'):
        if file.is_file():
            checksum_manifest[str(file.relative_to(destination))] = sha256_file(file)
    (destination / 'checksums.sha256.json').write_text(
        json.dumps(checksum_manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'PRIVATE BACKUP READY: {destination}')
    print(f'Durable Object keys: {count}; R2 objects: {media_count}; total R2 bytes: {media_bytes}')
    print('WARNING: backup has personal data, password hashes and sessions. Keep it encrypted/offline.')
    print('NO DATA WAS DELETED. Admin must approve a separate reset plan after reviewing backup.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, urllib.error.URLError) as exc:
        print('BACKUP STOPPED: ' + str(exc), file=sys.stderr)
        sys.exit(1)
