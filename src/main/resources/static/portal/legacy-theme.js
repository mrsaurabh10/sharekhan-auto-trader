'use strict';
(function(){
  const statuses=new Set(['TRIGGERED','ENTRY_SUBMITTING','PLACED_PENDING_CONFIRMATION','EXECUTED','REJECTED','COMPLETED','CANCELLED','FAILED','EXIT_TRIGGERED','TARGET_ORDER_PLACED','EXIT_ORDER_PLACED','EXITED_SUCCESS','EXIT_FAILED','ACTIVE']);
  function apply(){
    for(const table of document.querySelectorAll('table')){
      if(!table.closest('.table-scroll')){const wrap=document.createElement('div');wrap.className='table-scroll';wrap.tabIndex=0;wrap.setAttribute('role','region');wrap.setAttribute('aria-label',(table.id || 'Trading').replaceAll('-',' ')+' table');table.before(wrap);wrap.append(table);}
      for(const cell of table.querySelectorAll('tbody td')){
        if(cell.childElementCount || !statuses.has(cell.textContent.trim()))continue;
        const text=cell.textContent.trim(),pill=document.createElement('span');pill.textContent=text;
        pill.className='status-pill '+(/FAILED|REJECTED|CANCELLED/.test(text)?'status-bad':/EXECUTED|COMPLETED|SUCCESS|ACTIVE/.test(text)?'status-good':'status-wait');cell.replaceChildren(pill);
      }
    }
  }
  let queued=false;const observer=new MutationObserver(()=>{if(queued)return;queued=true;requestAnimationFrame(()=>{queued=false;apply();});});
  observer.observe(document.body,{childList:true,subtree:true});apply();
  // Legacy logout links use the same CSRF-protected POST as the new portal.
  document.addEventListener('click',async event=>{const link=event.target.closest('a[href="/logout"]');if(!link)return;event.preventDefault();
    try{const res=await fetch('/api/auth/csrf-token',{credentials:'same-origin'});if(!res.ok)throw new Error();const csrf=await res.json();
      const form=document.createElement('form');form.action='/logout';form.method='POST';const token=document.createElement('input');token.type='hidden';token.name=csrf.parameter;token.value=csrf.token;form.append(token);document.body.append(form);form.submit();
    }catch{window.alert('Unable to sign out. Refresh the page and try again.');}
  });
})();
