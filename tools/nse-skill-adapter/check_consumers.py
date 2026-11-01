#!/usr/bin/env python3
"""Check installed offline skill readers preserve every exported bar; no screening."""
import argparse
from dataclasses import asdict, is_dataclass
import importlib.util
import json
from pathlib import Path
import sys
import adapter

READERS = [
    ('stockbee-momentum-burst-screener', 'screen_momentum_burst.py', 'read_prices_json'),
    ('stockbee-exhaustion-hammer-screener', 'screen_exhaustion_hammer.py', 'read_prices_json'),
    ('stockbee-episodic-pivot-analyzer', 'analyze_ep.py', 'load_prices_json'),
    ('stockbee-20pct-study', 'run_20pct_study.py', 'load_prices_json'),
    ('stockbee-setup-fluency-trainer', 'build_model_book.py', 'load_prices_json')]


def no_network(event, args):
    if event in ('socket.connect', 'socket.getaddrinfo', 'subprocess.Popen'):
        raise RuntimeError('network/subprocess forbidden during compatibility check')


def check(bundle, root):
    manifest, expected = adapter.verify_bundle(bundle)
    if not expected:
        raise adapter.InputError('empty input cannot establish compatibility')
    results = []
    sys.addaudithook(no_network)
    for skill, filename, reader in READERS:
        spec = importlib.util.spec_from_file_location('checked_' + skill.replace('-','_'), root / skill / 'scripts' / filename)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        actual = getattr(module, reader)(str(bundle / 'prices.json'))
        if set(actual) != set(expected):
            raise adapter.InputError('consumer changed the symbol set: ' + skill)
        count = 0
        for symbol, rows in actual.items():
            normalized = [asdict(row) if is_dataclass(row) else row for row in rows]
            normalized = [{k: row[k] for k in ('date','open','high','low','close','volume')} for row in normalized]
            if sorted(normalized,key=lambda r:r['date']) != expected[symbol]:
                raise adapter.InputError('consumer changed/dropped prices or dates: ' + skill)
            count += len(rows)
        results.append({'skill': skill, 'symbols': len(actual), 'bars': count, 'result': 'LOSSLESS_READER_PASS'})
    return {'as_of': manifest['as_of'], 'source_sha256': manifest['source_sha256'],
            'network': 'FORBIDDEN', 'scope': 'reader compatibility only, not NSE strategy validation', 'readers': results}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', required=True,type=Path)
    parser.add_argument('--skills-root', required=True,type=Path)
    args = parser.parse_args()
    print(json.dumps(check(args.bundle,args.skills_root),indent=2))
