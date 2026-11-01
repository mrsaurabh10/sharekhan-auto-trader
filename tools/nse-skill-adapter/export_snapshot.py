#!/usr/bin/env python3
"""Explicit read-only SSH export; no broker requests or credentials in output."""
import argparse
from datetime import date
import os
from pathlib import Path
import shlex
import subprocess
import tempfile


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--as-of', required=True, type=date.fromisoformat)
    p.add_argument('--output', required=True, type=Path)
    args = p.parse_args()
    if args.output.exists():
        p.error('output already exists; choose a new snapshot path')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    remote = shlex.join(['docker', 'exec', '-i', 'sharekhan-postgres', 'sh', '-c',
        'exec psql -XqAt -v ON_ERROR_STOP=1 -v as_of=' + args.as_of.isoformat()
        + ' -U "$POSTGRES_USER" -d "$POSTGRES_DB"'])
    fd, name = tempfile.mkstemp(prefix='.snapshot-', dir=args.output.parent)
    try:
        with os.fdopen(fd, 'wb') as dest:
            result = subprocess.run(['ssh', '-o', 'BatchMode=yes', 'sharekhan-prod', remote],
                input=Path(__file__).with_name('export.sql').read_bytes(), stdout=dest,
                stderr=subprocess.PIPE, timeout=120)
        if result.returncode:
            raise RuntimeError('read-only SSH/psql export failed; snapshot not published')
        if not 0 < Path(name).stat().st_size <= 100 * 1024 * 1024:
            raise RuntimeError('snapshot size outside supported bounds')
        # Hard link refuses to overwrite a concurrently created destination.
        os.link(name, args.output)
        print('Saved market-data snapshot:', args.output)
    finally:
        Path(name).unlink(missing_ok=True)


if __name__ == '__main__':
    main()
