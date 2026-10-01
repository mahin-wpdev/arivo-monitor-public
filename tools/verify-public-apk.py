"""Refuse to publish an APK containing locally configured production credentials."""
import argparse
import hashlib
import re
import zipfile
from pathlib import Path


def read_secrets(files):
    secrets = []
    for file in files:
        if not Path(file).is_file():
            raise SystemExit('Credential scan configuration file missing')
        for line in Path(file).read_text(encoding='utf-8').splitlines():
            if '=' not in line or line.lstrip().startswith('#'):
                continue
            name, value = line.split('=', 1)
            if re.search(r'PASSWORD|DEVICE_KEY|TOKEN|SECRET', name, re.I):
                value = value.strip().strip('"\'')
                if len(value) >= 8:
                    secrets.append(value.encode())
    return secrets


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', required=True)
    parser.add_argument('--runtime-config', required=True)
    parser.add_argument('--server-env', required=True)
    args = parser.parse_args()
    secrets = read_secrets([args.runtime_config, args.server_env])
    if not secrets:
        raise SystemExit('No configured credentials available for verification')
    patterns = rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}'
    with zipfile.ZipFile(args.apk) as archive:
        for entry in archive.namelist():
            data = archive.read(entry)
            if any(secret in data for secret in secrets) or re.search(patterns, data):
                raise SystemExit('Credential material found in APK; publication refused')
    print('APK production credential scan passed')
    print('SHA256=' + hashlib.sha256(Path(args.apk).read_bytes()).hexdigest())
