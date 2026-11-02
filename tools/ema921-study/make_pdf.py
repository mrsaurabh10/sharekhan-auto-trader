from pathlib import Path
import sys
import json
from reportlab.platypus import SimpleDocTemplate,Paragraph,Spacer,Table,TableStyle,PageBreak
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet,ParagraphStyle
from reportlab.lib.pagesizes import A4,landscape
P=Path(sys.argv[1]).resolve()/"outputs/ema921-20261102";r=json.loads((P/'results.json').read_text());styles=getSampleStyleSheet();styles.add(ParagraphStyle(name='Note',fontName='Helvetica',fontSize=9,leading=13,spaceAfter=9,textColor=colors.HexColor('#35475d')))
story=[]
def p(s,style='Note'):story.append(Paragraph(s,styles[style]))
def table(headers,rows,widths):
 data=[[Paragraph(str(x),styles['Note']) for x in headers]]+[[str(x) for x in row] for row in rows]
 t=Table(data,colWidths=widths,repeatRows=1,hAlign='LEFT');t.setStyle(TableStyle([('FONTNAME',(0,0),(-1,-1),'Helvetica'),('FONTSIZE',(0,0),(-1,-1),8),('LEADING',(0,1),(-1,-1),9),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#e4edf3')),('TEXTCOLOR',(0,0),(-1,-1),colors.HexColor('#172b42')),('BOTTOMPADDING',(0,0),(-1,0),4),('TOPPADDING',(0,1),(-1,-1),2),('BOTTOMPADDING',(0,1),(-1,-1),2),('LINEBELOW',(0,0),(-1,-1),.3,colors.HexColor('#d2dce5')),('ALIGN',(2,1),(-1,-1),'RIGHT')]));story.append(t);story.append(Spacer(1,12))
def f(n):return '-' if n is None else f'{n:,.2f}'
p('NIFTY EMA 9/21 - ATM backtest','Title');p('2 November 2026 | NSE / IST | Saved data cutoff 30 October 2026 | One conditional historical lot')
p('<b>Conditional sample only. This does not establish a live-trading edge.</b> Pricing coverage is only 33-37% of candidate trades. Exact expiry/security IDs are absent from the rolling source. Do not interpret accepted-subset totals as full-strategy or actual account returns.')
rows=[]
for tf in [5,15,30]:
 for mode in ['ATM_BUY','ATM_SELL']:
  ss={s['scenario']:s for s in r['summary'] if s['timeframe']==tf and s['mode']==mode};g,b,z=ss['gross'],ss['base'],ss['stress']
  rows.append([tf,'Buy' if mode=='ATM_BUY' else 'Sell',f"{g['accepted']}/{g['candidateTrades']}",f(g['coveragePercent'])+'%',f(g['pnl']),f(b['pnl']),f(z['pnl']),f(b['winRate'])+'%',f(b['profitFactor'])])
table(['Minutes','ATM mode','Subset / signals','Coverage','Gross INR','Base net INR','Stress net INR','Net win rate','Net PF'],rows,[45,55,90,65,100,100,100,80,65])
p('<b>Finding:</b> 5m and 15m accepted samples lose before and after modeled costs. 30m ATM sell yields gross INR 22,066.25 and modeled base net INR 3,122.45, but stress net is INR -5,794.20. The small base result is not robust to doubled slippage.')
p('<b>Baseline:</b> closed 5/15/30m spot bars aligned 09:15; EMA9/21 separately SMA-seeded; entry window 09:30-14:30 inclusive. Enter at the exact next minute open after a closed-bar cross. Opposite crosses exit, reversing only inside the entry window. Mandatory 15:00 exit takes priority. No stop or target is specified. ATM is nearest 50 at signal close, ties upward; bullish buy CE / sell PE, bearish buy PE / sell CE.')
p('<b>Base / stress:</b> 0.5% / 1% adverse premium slippage on each fill, rounded adversely to 0.05 tick. Both use the explicit hypothetical full fee stack on page 3. These are modeled costs, not reconstructed historical tariffs.')
p('Interactive report: report.html. Trade charts: audit-5.html, audit-15.html, audit-30.html. Full prices, lots, individual costs and exclusions are supplied in trades.csv and exclusions.csv.')
story.append(PageBreak());p('Yearly accepted samples - modeled base net','Title');p('Years are partial and uneven. The absent middle coverage is not a zero-return year. No parameter selection, untouched holdout or portfolio aggregation is implied.')
annual=[]
for s in sorted((x for x in r['yearly'] if x['scenario']=='base'),key=lambda x:(x['period'],x['timeframe'],x['mode'])):
 annual.append([s['period'],s['timeframe'],'Buy' if s['mode']=='ATM_BUY' else 'Sell',s['trades'],f(s['pnl']),f(s['winRate'])+'%',f(s['profitFactor']),f(s['maxDrawdown'])])
table(['Year','Minutes','ATM mode','Trades','Modeled net INR','Net wins','Net PF','Max DD INR'],annual,[60,60,70,65,140,100,90,115])
story.append(PageBreak());p('Coverage, sizing and audit','Title')
p('<b>Spot:</b> saved Dhan IDX_I / security 13 NIFTY native 1m candles, quality NATIVE_5M_WINDOW_CONFIRMED. 1,174 complete regular sessions from Nov2021-Oct2026; 58 observed incomplete dates rejected. Every accepted session has all 375 minute slots. Rejected days and gaps over seven days reset EMA history. Missing whole sessions and holiday calendars remain unverified.')
p('<b>Options:</b> saved Dhan NSE_FNO / NIFTY / expiryCode1 rolling WEEK family. 3,485 compressed NIFTY chunks passed SHA256 checks. Older Nov2021-earlyFeb2023 and Nov2025-Oct2026 coverage; a newer server annual import is not included in this retained snapshot. There are 2,898 accepted mode observations and 5,134 exclusions across the six variants. Requires exact ATM entry, same-strike exit, positive endpoint volume and every intervening fixed-strike minute. No new ATM strike or missing tail is substituted.')
p('<b>Lots:</b> canonical references/index-option-lot-sizes.json, verified from six NSE circulars. One lot: 50 units in the older sample, 75 in nearest Nov/Dec2025 family, 65 for Jan2026-onward nearest family. Actual expiry/security IDs remain missing, so all assignments are CONDITIONAL_NEAREST_FAMILY. 18 mode observations during 24-30Dec2025 have quantity bounds 65/75. The PDF constant65 is not applied across history.')
p('<b>Modeled fee assumptions:</b> INR20 per order, two orders per trade; STT0.15% of sell premium turnover; exchange0.05% both sides; SEBI0.0001% both sides; stamp0.003% buy; IPFT0.0005% both sides; GST18% on brokerage+exchange+SEBI+IPFT. Rates are deliberately a fixed hypothetical scenario across dates and may differ materially from the historical broker schedule. Per-charge paise rounding may differ from contract notes. Costs are charged on slipped prices. No exercised-option settlement is modeled.')
p('<b>Risk and metrics:</b> realized sequential drawdown only; no intratrade excursions, order depth, bid/ask, rejection model, margin feasibility or defensible percentage capital drawdown. Short ATM options have no defined stop. Selection bias from missing prices can materially change the result in either direction. Profit factor, payoff, expectancy, gross/net win rates and monthly/yearly results are in the CSV/HTML report. No live recommendation, activation, order, account change, scheduler or deployment.')
p('<b>Validation:</b> independent Python replay checked 4,016 Java signal trades and 131,488 resampled candles. Accounting audit checked all 2,898 accepted mode observations and 18 summary rows. Focused Java/MVC and browser checks are retained separately. ITM5 engine support exists but no ITM5 history was backtested because saved rolling coverage is only ATM +/-2.')
p('<b>Sources:</b> supplied 9-21 EMA Cross Over - Algo Trading.pdf; saved project archive and lot reference. Current fee-category references: <link href="https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax" color="blue">NSE STT</link>, <link href="https://www.nseindia.com/static/invest/first-time-investor-sebi-turnover-fees-stt-other-levies" color="blue">NSE levies</link>, <link href="https://zerodha.com/charges/" color="blue">broker charges reference</link>, <link href="https://dhanhq.co/docs/v2/expired-options-data/" color="blue">Dhan schema</link>. These sources do not verify missing historical contract identity or the modeled fee scenario.')
def footer(canvas,doc):
 canvas.setFont('Helvetica',8);canvas.setFillColor(colors.HexColor('#52657d'));canvas.drawString(35,20,'NIFTY EMA9/21 | Conditional research | 2 November 2026');canvas.drawRightString(805,20,str(doc.page))
SimpleDocTemplate(str(P/'nifty-ema921-backtest.pdf'),pagesize=landscape(A4),leftMargin=35,rightMargin=35,topMargin=28,bottomMargin=32,title='NIFTY EMA9/21 ATM backtest - conditional sample').build(story,onFirstPage=footer,onLaterPages=footer)
