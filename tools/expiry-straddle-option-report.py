#!/usr/bin/env python3
"""Offline straddle replay from public market candles already fetched by an operator.

No network, credentials, login, broker mutation, or model-based option pricing.
"""
import argparse
import csv
import datetime as dt
import json
from decimal import Decimal, ROUND_FLOOR
from pathlib import Path

D = Decimal
SESSIONS = {'AM': ('09:30:00', '10:30:00'), 'PM': ('13:30:00', '14:30:00')}


def price(raw):
    value = D(str(raw))
    if not value.is_finite() or value <= 0:
        raise ValueError('Missing or invalid market price')
    return value


def calculate(spot_rows, option_data, lots=1, strike_step=50):
    if not isinstance(lots, int) or not 1 <= lots <= 1000:
        raise ValueError('Lots must be an integer from 1 to 1000')
    date = dt.date.fromisoformat(option_data['date'])
    if option_data['expiry'] != str(date):
        raise ValueError('Only expiry-day options are eligible')
    lot_size = int(option_data['lot_size'])
    if lot_size <= 0 or strike_step <= 0:
        raise ValueError('Invalid lot size or strike step')
    quantity = lots * lot_size
    spots = {}
    for row in spot_rows:
        stamp = dt.datetime.fromisoformat(row['candle_time'])
        if stamp.date() == date:
            if stamp.time().isoformat() in spots:
                raise ValueError('Duplicate spot timestamp')
            spots[stamp.time().isoformat()] = price(row['open_price'])
    legs, sessions = [], []
    for slot, (entry, exit_time) in SESSIONS.items():
        spot = spots[entry]
        lower = (spot / strike_step).to_integral_value(rounding=ROUND_FLOOR) * strike_step
        strike = lower if spot - lower <= lower + strike_step - spot else lower + strike_step
        selected = [row for row in option_data['contracts'] if row['session'] == slot and D(str(row['strike'])) == strike]
        if len(selected) != 2 or {row['side'] for row in selected} != {'CE', 'PE'}:
            raise ValueError('Both ATM legs at the fresh session strike are required')
        entry_sum, exit_sum = D('0'), D('0')
        for contract in sorted(selected, key=lambda c: c['side']):
            bars = {}
            for row in contract.get('candles', []):
                timestamp = dt.datetime.strptime(row['time'], '%d-%m-%Y %H:%M:%S')
                if timestamp.date() != date:
                    continue
                key = timestamp.time().isoformat()
                if key in bars:
                    raise ValueError('Duplicate option timestamp')
                bars[key] = row
            # Fail if a boundary is missing; never use a later quote or another candle.
            buy, sell = price(bars[entry]['into']), price(bars[exit_time]['into'])
            entry_sum += buy
            exit_sum += sell
            points = sell - buy
            legs.append({'date': str(date), 'session': slot, 'expiry': str(date), 'strike': str(strike),
                         'side': contract['side'], 'entry_time': entry, 'exit_time': exit_time,
                         'entry_premium': str(buy), 'exit_premium': str(sell), 'premium_points': str(points),
                         'quantity': quantity, 'gross_pnl': str(points * quantity)})
        points = exit_sum - entry_sum
        sessions.append({'date': str(date), 'session': slot, 'strike': str(strike), 'lots': lots, 'quantity_per_leg': quantity,
                         'entry_premium_sum': str(entry_sum), 'exit_premium_sum': str(exit_sum),
                         'premium_points': str(points), 'entry_debit': str(entry_sum * quantity),
                         'exit_credit': str(exit_sum * quantity), 'gross_pnl': str(points * quantity)})
    return {'date': str(date), 'provider': option_data['provider'], 'lot_size': lot_size,
            'lots': lots, 'legs': legs, 'sessions': sessions,
            'total_gross_pnl': str(sum((D(row['gross_pnl']) for row in sessions), D(0))),
            'assumptions': 'Historical one-minute candle opens at exact scheduled boundaries; fresh ATM each session; no commissions, taxes, spread or slippage; hypothetical fills, not executed trades.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--spot-csv', required=True, type=Path)
    parser.add_argument('--option-json', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--lots', type=int, default=1)
    args = parser.parse_args()
    with args.spot_csv.open() as source:
        report = calculate(csv.DictReader(source), json.loads(args.option_json.read_text()), args.lots)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for kind in ['legs', 'sessions']:
        with (args.output_dir / ('option-' + kind + '.csv')).open('w') as output:
            writer = csv.DictWriter(output, fieldnames=list(report[kind][0]))
            writer.writeheader()
            writer.writerows(report[kind])
    (args.output_dir / 'option-results.json').write_text(json.dumps(report, indent=2) + '\n')
    text = ('NIFTY expiry long straddle — ' + report['date'] + '\n\n'
            'Lots: ' + str(report['lots']) + '; units per lot per leg: ' + str(report['lot_size']) + '\n'
            + '\n'.join(row['session'] + ': strike ' + row['strike'] + ', entry premium sum ' + row['entry_premium_sum']
                        + ', exit premium sum ' + row['exit_premium_sum'] + ', gross P&L INR ' + row['gross_pnl'] for row in report['sessions'])
            + '\nTotal gross P&L INR ' + report['total_gross_pnl'] + '\n\n' + report['assumptions'] + '\n')
    (args.output_dir / 'option-report.txt').write_text(text)
    print(text)


if __name__ == '__main__':
    main()
