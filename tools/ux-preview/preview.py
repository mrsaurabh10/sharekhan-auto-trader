from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import re
import argparse

def legacy_snapshot():
 s=(root/'static/admin-dashboard.html').read_text()
 s=re.sub(r'<script\b[^>]*>.*?</script>','',s,flags=re.S)
 s=s.replace('</head>','<script src="/portal/legacy-theme.js" defer></script></head>')
 s=s.replace('<h2 id="dashboardTitle">Admin Dashboard</h2>','<h2 id="dashboardTitle">AutoTrader · Trading workspace <small style="font-size:12px;color:#66748b">UX preview · Sample data</small></h2>')
 s=s.replace('id="userContent" style="display:none"','id="userContent" style="display:block"')
 s=s.replace('<div id="usersContainer">Loading users...</div>','<div id="usersContainer"><ul><li class="active">Alex Morgan</li><li>Jamie Patel</li></ul></div>')
 s=s.replace('<div id="userTabs" style="margin-bottom:0.75rem;display:flex;gap:6px;flex-wrap:wrap"></div>','<div id="userTabs" style="margin-bottom:0.75rem;display:flex;gap:6px;flex-wrap:wrap"><button class="tab-btn active">Trading workspace</button><button class="tab-btn">Brokers</button><button class="tab-btn">Settings</button></div>')
 # Hide the bulky legacy order composer to focus this preview on the requested tables.
 s=s.replace('<div id="placeOrderPanel">','<div id="placeOrderPanel" style="display:none">')
 for ident in ['user-requests-table','user-executed-table']:
  pattern=r'(<table id="'+ident+r'".*?<thead>.*?</thead>\s*<tbody>).*?(</tbody>)'
  m=re.search(pattern,s,re.S)
  if m:
   heads=re.findall(r'<th[^>]*>(.*?)</th>',m.group(1),re.S)
   values={'ID':'1042','Date':'14 Oct 2026','Type':'Own','Symbol':'NIFTY 25000 CE','Exchange':'NF','Strike':'25000','Option Type':'CE','Entry':'182.50','SL':'160.00','T1':'220.00','Qty':'75','Status':'EXECUTED' if 'executed' in ident else 'PLACED_PENDING_CONFIRMATION','Action':'<button class="btn">View details</button>','LTP':'202.50','Spot LTP':'25120.00','Gross PNL':'1,500.00','Trade Cost':'75.00','Effective PNL':'1,425.00'}
   row='<tr>'+''.join('<td>'+values.get(re.sub('<[^>]+>','',h),'—')+'</td>' for h in heads)+'</tr>'
   s=s[:m.start()]+m.group(1)+row+row+m.group(2)+s[m.end():]
 return s

root=Path(__file__).resolve().parents[2]/'src/main/resources'
class Preview(BaseHTTPRequestHandler):
 def do_POST(self):
  # Preview forms never persist input or call a broker, account service, or SMTP server.
  self.send_response(200 if self.path.startswith('/api/account/') else 405)
  self.send_header('Content-Type','application/json');self.end_headers()
  self.wfile.write(b'{"message":"UX preview only. No account, password, or broker data was saved."}')

 def log_message(self,*args): pass
 def do_GET(self):
  path=self.path.split('?')[0]
  if path in ['/', '/dashboard','/login','/account']:
   name={'/login':'admin-login','/account':'account'}.get(path,'portal')
   data=(root/'templates'/f'{name}.html').read_text()
   data=re.sub(r'<div\b[^>]*th:if="\$\{param\.[^"]+\}"[^>]*>.*?</div>','',data,flags=re.S)
   if name=='portal': data=data.replace('<script src="/portal/portal.js"', '<script src="/preview-fixtures.js"></script><script src="/portal/portal.js"')
   data=data.encode(); content='text/html'
  elif path=='/api/auth/csrf-token': data=b'{"header":"X-CSRF-TOKEN","parameter":"_csrf","token":"preview-only"}';content='application/json'
  elif path=='/api/account/availability': data=b'{"enabled":true}';content='application/json'
  elif path=='/legacy': data=legacy_snapshot().encode();content='text/html'
  elif path=='/preview-fixtures.js': data=Path(__file__).with_name('fixtures.js').read_bytes();content='application/javascript'
  elif path.startswith('/portal/') and '..' not in path:
   p=root/'static'/path.lstrip('/'); data=p.read_bytes();content='text/css' if p.suffix=='.css' else 'application/javascript'
  else: self.send_error(404);return
  self.send_response(200);self.send_header('Content-Type',content);self.end_headers();self.wfile.write(data)
if __name__ == '__main__':
 parser=argparse.ArgumentParser(description='Read-only AutoTrader UX preview. All data is synthetic.')
 parser.add_argument('--port',type=int,default=8766)
 args=parser.parse_args()
 print(f'UX preview: http://127.0.0.1:{args.port}/dashboard (sample data only)',flush=True)
 HTTPServer(('127.0.0.1',args.port),Preview).serve_forever()
