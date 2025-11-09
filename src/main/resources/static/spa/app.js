(function(){
  const resp = id => document.getElementById(id);
  const submitBtn = resp('submitBtn');
  const responseArea = resp('responseArea');
  const apiResult = resp('apiResult');

  function jsonFetch(url, opts){
    return fetch(url, Object.assign({headers:{'Content-Type':'application/json'}}, opts)).then(r=>r.json());
  }

  submitBtn.onclick = async function(){
    const payload = {
      instrument: document.getElementById('instrument').value,
      exchange: document.getElementById('exchange').value,
      entryPrice: parseFloat(document.getElementById('entryPrice').value) || null,
      stopLoss: parseFloat(document.getElementById('stopLoss').value) || null,
      target1: parseFloat(document.getElementById('target1').value) || null,
      target2: parseFloat(document.getElementById('target2').value) || null,
      target3: parseFloat(document.getElementById('target3').value) || null,
      quantity: (function(){ const v=document.getElementById('quantity').value; return v?parseInt(v):null})(),
      strikePrice: (function(){ const v=document.getElementById('strikePrice').value; return v?parseFloat(v):null})(),
      optionType: document.getElementById('optionType').value || null,
      expiry: document.getElementById('expiry').value || null,
      intraday: true
    };

    responseArea.textContent = 'Posting...';
    try{
      const res = await fetch('/api/trades/trigger-on-price',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
      if (!res.ok) {
        const text = await res.text();
        responseArea.textContent = 'ERROR ' + res.status + '\n' + text;
        return;
      }
      const data = await res.json();
      responseArea.textContent = JSON.stringify(data,null,2);
    }catch(e){ responseArea.textContent = 'Fetch failed: '+ e.message }
  };

  document.getElementById('listRecent').onclick = async () => {
    apiResult.textContent = 'Loading...';
    try{
      const r = await fetch('/api/trades/recent-executions');
      const j = await r.json();
      apiResult.textContent = JSON.stringify(j,null,2);
    }catch(e){ apiResult.textContent = 'Err: '+ e.message }
  };

  document.getElementById('listRequests').onclick = async () => {
    apiResult.textContent = 'Loading...';
    try{
      const r = await fetch('/api/trades/pending');
      const j = await r.json();
      apiResult.textContent = JSON.stringify(j,null,2);
    }catch(e){ apiResult.textContent = 'Err: '+ e.message }
  };
})();
